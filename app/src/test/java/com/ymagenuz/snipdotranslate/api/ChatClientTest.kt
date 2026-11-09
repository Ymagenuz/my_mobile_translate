package com.ymagenuz.snipdotranslate.api

import com.ymagenuz.snipdotranslate.core.ApiProviderSpec
import com.ymagenuz.snipdotranslate.core.OPENAI_COMPATIBLE_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.OPENROUTER_API_PROVIDER
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatClientTest {
    private val server = MockWebServer()
    private val client = ChatClient()
    private lateinit var provider: ApiProviderSpec

    @Before
    fun start() {
        server.start()
        provider = ApiProviderSpec(
            providerId = OPENAI_COMPATIBLE_API_PROVIDER,
            displayName = "test",
            shortName = "test",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            model = "test-model",
            supportsVision = true,
        )
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun delta(text: String) =
        "data: " + buildString {
            append("""{"choices":[{"delta":{"content":""")
            append(Json.encodeToString(text))
            append("}}]}")
        } + "\n\n"

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString(""))

    private fun streamed(prompt: String = "hi") = runBlocking {
        client.streamChat(provider, "test-key", prompt).toList()
    }

    private fun requestJson(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun streamsTextInOrder() {
        server.enqueue(sse(delta("# 译文"), delta("\n\n- 项目"), "data: [DONE]\n\n"))
        assertEquals(listOf("# 译文", "\n\n- 项目"), streamed())
    }

    @Test
    fun multiByteTextSplitAcrossNetworkChunksStaysIntact() {
        val response = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setChunkedBody(delta("翻译结果😀") + "data: [DONE]\n\n", 3)
        server.enqueue(response)
        assertEquals("翻译结果😀", streamed().joinToString(""))
    }

    @Test
    fun streamingRequestHasTheExpectedShape() {
        server.enqueue(sse("data: [DONE]\n\n"))
        streamed("the prompt")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        val message = body.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertEquals("the prompt", message.getValue("content").jsonPrimitive.content)
        assertNull(body["thinking"])
    }

    @Test
    fun thinkingOptionsAreSentForEachProvider() {
        server.enqueue(sse("data: [DONE]\n\n"))
        server.enqueue(sse("data: [DONE]\n\n"))
        val thinkingOff = provider.copy(disableThinking = true)

        runBlocking { client.streamChat(thinkingOff, "k", "p").toList() }
        runBlocking { client.streamChat(thinkingOff.copy(providerId = OPENROUTER_API_PROVIDER), "k", "p").toList() }

        assertEquals("disabled", requestJson().getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("none", requestJson().getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun irrelevantStreamLinesAreSkipped() {
        server.enqueue(
            sse(
                ": OPENROUTER PROCESSING\n\n",
                "event: ping\n\n",
                "data: not json\n\n",
                """data: {"choices":[]}""" + "\n\n",
                """data: {"choices":[{"delta":{"content":null}}]}""" + "\n\n",
                """data: {"choices":[{"delta":{"role":"assistant"}}]}""" + "\n\n",
                delta("kept"),
                "data: [DONE]\n\n",
                delta("after done"),
            ),
        )
        assertEquals(listOf("kept"), streamed())
    }

    @Test
    fun acceptsDataLinesWithoutASpaceOrBlankSeparators() {
        server.enqueue(sse("""data:{"choices":[{"delta":{"content":"a"}}]}""" + "\n", delta("b").trimEnd() + "\n"))
        assertEquals(listOf("a", "b"), streamed())
    }

    @Test
    fun httpErrorsCarryTheProvidersMessage() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key"}}"""))

        val error = assertThrows(ApiException::class.java) { streamed() }

        assertEquals("Invalid key", error.message)
        assertEquals(401, error.statusCode)
    }

    @Test
    fun httpErrorsWithoutDetailStillSaySomething() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>busy</html>"))
        assertEquals("HTTP 503", assertThrows(ApiException::class.java) { streamed() }.message)
    }

    @Test
    fun errorsReportedInsideTheStreamFailIt() {
        server.enqueue(sse(delta("partial"), """data: {"error":{"message":"overloaded"}}""" + "\n\n"))

        val received = mutableListOf<String>()
        var failure: Throwable? = null
        runBlocking {
            client.streamChat(provider, "k", "p").catch { failure = it }.collect { received += it }
        }

        assertEquals(listOf("partial"), received)
        assertEquals("overloaded", failure?.message)
    }

    @Test
    fun aDroppedConnectionKeepsWhatArrivedAndFails() {
        val body = delta("partial") + delta("x".repeat(4096))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("Content-Length", body.length * 4)
                .setBody(body)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val received = mutableListOf<String>()
        var failure: Throwable? = null
        runBlocking { client.streamChat(provider, "k", "p").catch { failure = it }.collect { received += it } }

        assertEquals("partial", received.first())
        assertTrue("expected a failure", failure is java.io.IOException)
    }

    @Test
    fun cancellingStopsTheCallPromptly() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(delta("first") + delta("y".repeat(100_000)))
                .throttleBody(32, 1, TimeUnit.SECONDS),
        )

        val first = runBlocking { withTimeout(10_000) { client.streamChat(provider, "k", "p").first() } }

        assertEquals("first".take(first.length), first)
    }

    @Test
    fun completeReturnsTheMessageAndSendsImages() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"read text"}}]}"""))

        val reply = runBlocking {
            client.complete(provider, "k", visionMessageContent("ocr prompt", "data:image/jpeg;base64,AAAA"))
        }

        assertEquals("read text", reply)
        val body = requestJson()
        assertEquals("false", body.getValue("stream").jsonPrimitive.content)
        val content = body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("ocr prompt", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun completeReportsApiErrors() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))

        val error = assertThrows(ApiException::class.java) {
            runBlocking { client.complete(provider, "k", visionMessageContent("p", "d")) }
        }

        assertEquals("slow down", error.message)
        assertEquals(429, error.statusCode)
    }

    @Test
    fun modelListIsFetchedWithTheKey() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"}]}"""))

        assertEquals(listOf("a", "b"), runBlocking { client.listModels(provider, "test-key") })

        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
    }

    @Test
    fun unusableModelListsGiveActionableErrors() {
        server.enqueue(MockResponse().setBody("not json"))
        val error = assertThrows(ApiException::class.java) { runBlocking { client.listModels(provider, "k") } }
        assertTrue("Enter a model ID manually" in error.message.orEmpty())
    }

    @Test
    fun keysThatCantBeSentFailCleanlyWithoutEchoingTheKey() {
        val error = assertThrows(ApiException::class.java) {
            runBlocking { client.streamChat(provider, "bad中key", "p").toList() }
        }
        assertTrue("bad" !in error.message.orEmpty())
    }
}

package com.ymagenuz.snipdotranslate.api

import com.ymagenuz.snipdotranslate.core.ApiProviderSpec
import com.ymagenuz.snipdotranslate.core.chatCompletionOptions
import com.ymagenuz.snipdotranslate.core.parseModelIds
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** An API failure with a message that is safe to show; it never contains the key. */
class ApiException(message: String, val statusCode: Int? = null) : IOException(message)

private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val STREAM_READ_TIMEOUT_SECONDS = 30L
private const val OCR_TIMEOUT_SECONDS = 90L
private const val KEEP_ALIVE_SECONDS = 110L
private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
private const val MAX_ERROR_MESSAGE_CHARS = 500
private const val DONE_MARKER = "[DONE]"

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .connectionPool(ConnectionPool(5, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
    .build()

/** A message with a single image, as used for reading text with a vision model. */
fun visionMessageContent(prompt: String, imageDataUrl: String): JsonElement = buildJsonArray {
    addJsonObject {
        put("type", "text")
        put("text", prompt)
    }
    addJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") { put("url", imageDataUrl) }
    }
}

/** OpenAI-compatible Chat Completions, as every provider in the app speaks it. */
class ChatClient(private val http: OkHttpClient = createHttpClient()) {
    private val longCalls: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Emits text as it arrives. Text received before a failure is still emitted.
     * Cancelling the collector cancels the HTTP call, so the model stops generating.
     */
    fun streamChat(provider: ApiProviderSpec, apiKey: String, prompt: String): Flow<String> = callbackFlow {
        val call = try {
            http.newCall(chatRequest(provider, apiKey, JsonPrimitive(prompt), streaming = true))
        } catch (_: IllegalArgumentException) {
            close(invalidRequest())
            awaitClose { }
            return@callbackFlow
        }
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw apiError(response)
                    val source = response.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val data = dataOf(line) ?: continue
                        if (data == DONE_MARKER) break
                        val event = parseObject(data) ?: continue
                        streamError(event)?.let { throw it }
                        deltaContent(event)?.let { send(it) }
                    }
                }
                channel.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                channel.close(failure)
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    /** Sends a whole request and returns the reply; used for reading images. */
    suspend fun complete(provider: ApiProviderSpec, apiKey: String, content: JsonElement): String {
        val request = try {
            chatRequest(provider, apiKey, content, streaming = false)
        } catch (_: IllegalArgumentException) {
            throw invalidRequest()
        }
        val body = longCalls.newCall(request).awaitBody()
        val reply = parseObject(body) ?: throw ApiException("The API returned an invalid response.")
        val message = firstChoice(reply)?.get("message") as? JsonObject
        return (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    }

    suspend fun listModels(provider: ApiProviderSpec, apiKey: String): List<String> {
        val request = try {
            Request.Builder()
                .url("${provider.baseUrl}/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            throw invalidRequest()
        }
        val body = http.newCall(request).awaitBody()
        val payload = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            throw ApiException("The API returned an invalid model list. Enter a model ID manually or check the API base URL.")
        }
        return try {
            parseModelIds(payload)
        } catch (unusable: IllegalArgumentException) {
            throw ApiException(unusable.message.orEmpty())
        }
    }

    private fun chatRequest(
        provider: ApiProviderSpec,
        apiKey: String,
        content: JsonElement,
        streaming: Boolean,
    ): Request {
        val options = chatCompletionOptions(provider, streaming)
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", content)
                }
            })
            put("model", options.model)
            put("stream", streaming)
            options.extraBody?.forEach { (key, value) -> put(key, value) }
        }
        val builder = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        options.headers.forEach { (name, value) -> builder.header(name, value) }
        // Compression makes intermediaries hold text back until a buffer fills.
        if (streaming) builder.header("Accept-Encoding", "identity")
        return builder.build()
    }

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw apiError(it)
                        continuation.resume(it.body.string())
                    }
                } catch (failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun invalidRequest() = ApiException("The API key or address contains characters that can't be sent.")

    private fun dataOf(line: String): String? =
        if (line.startsWith("data:")) line.removePrefix("data:").trim() else null

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun firstChoice(event: JsonObject): JsonObject? =
        (event["choices"] as? JsonArray)?.firstOrNull() as? JsonObject

    private fun deltaContent(event: JsonObject): String? {
        val delta = firstChoice(event)?.get("delta") as? JsonObject ?: return null
        return (delta["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    private fun streamError(event: JsonObject): ApiException? {
        val error = event["error"] as? JsonObject ?: return null
        return ApiException(errorText(error) ?: "The API reported an error.")
    }

    private fun apiError(response: Response): ApiException {
        val body = try {
            response.peekBody(MAX_ERROR_BODY_BYTES).string()
        } catch (_: IOException) {
            ""
        }
        val error = parseObject(body)?.get("error") as? JsonObject
        return ApiException(errorText(error) ?: "HTTP ${response.code}", response.code)
    }

    private fun errorText(error: JsonObject?): String? =
        (error?.get("message") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_ERROR_MESSAGE_CHARS)
}

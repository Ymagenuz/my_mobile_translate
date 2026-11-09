package com.ymagenuz.snipdotranslate.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    @Test
    fun providersMatchTheDesktopDefaults() {
        val gptsapi = apiProvider(DEFAULT_API_PROVIDER)
        assertEquals("https://api.gptsapi.net/v1", gptsapi.baseUrl)
        assertEquals("gpt-5.4-nano", gptsapi.model)

        val generic = apiProvider(OPENAI_COMPATIBLE_API_PROVIDER)
        assertEquals("https://api.openai.com/v1", generic.baseUrl)
        assertEquals("", generic.model)
        assertFalse(generic.supportsVision)
        assertTrue(generic.baseUrlEditable)

        assertTrue(apiProvider(DEEPSEEK_API_PROVIDER).disableThinking)
        assertEquals("openai/gpt-5.6-luna", apiProvider(OPENROUTER_API_PROVIDER).model)
    }

    @Test
    fun defaultConfigurationReconstructsEveryProvider() {
        API_PROVIDERS.forEach { provider ->
            assertEquals(provider, apiProvider(provider.providerId, providerDefaultConfig(provider.providerId)))
        }
    }

    @Test
    fun resolvedConfigurationIsNormalized() {
        val config = validatedProviderConfig(" https://example.org/custom/v1/// ", " chosen-model ", true, false)
        val resolved = apiProvider(DEEPSEEK_API_PROVIDER, config)

        assertEquals(DEEPSEEK_API_PROVIDER, resolved.providerId)
        assertEquals("https://example.org/custom/v1", resolved.baseUrl)
        assertEquals("chosen-model", resolved.model)
        assertTrue(resolved.supportsVision)
        assertNull(chatCompletionOptions(resolved).extraBody)
    }

    @Test
    fun thinkingIsDisabledThroughEachProvidersParameter() {
        val generic = apiProvider(
            OPENAI_COMPATIBLE_API_PROVIDER,
            ApiProviderConfig("https://example.org/v1", "local-model", false, true),
        )
        assertEquals(
            ChatCompletionOptions(
                model = "local-model",
                headers = mapOf("Accept" to "text/event-stream"),
                extraBody = buildJsonObject { putJsonObject("thinking") { put("type", "disabled") } },
            ),
            chatCompletionOptions(generic, streaming = true),
        )

        val openRouter = apiProvider(
            OPENROUTER_API_PROVIDER,
            ApiProviderConfig("https://openrouter.ai/api/v1", "m", true, true),
        )
        assertEquals(
            buildJsonObject { putJsonObject("reasoning") { put("effort", "none") } },
            chatCompletionOptions(openRouter, streaming = true).extraBody,
        )
    }

    @Test
    fun httpsAddressesAreAccepted() {
        listOf("https://example.org/v1", "https://example.org:8443/v1/", "https://[::1]:8080/v1", "https://my_host.example/v1")
            .forEach { assertEquals(it.trimEnd('/'), normalizeBaseUrl(it)) }
    }

    @Test
    fun plainHttpIsRefusedExceptLocallyWhenAllowed() {
        assertThrows(IllegalArgumentException::class.java) { normalizeBaseUrl("http://example.org/v1") }
        assertThrows(IllegalArgumentException::class.java) { normalizeBaseUrl("http://localhost:1234/v1") }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeBaseUrl("http://example.org/v1", allowLocalHttp = true)
        }
        listOf("http://localhost:1234/v1", "http://127.0.0.1:8080", "http://[::1]:8080/v1").forEach {
            assertEquals(it, normalizeBaseUrl(it, allowLocalHttp = true))
        }
    }

    @Test
    fun invalidOrSecretBearingAddressesAreRefused() {
        listOf(
            "",
            "/v1",
            "example.org/v1",
            "ftp://example.org/v1",
            "https:///v1",
            "https://",
            "https://user:secret@example.org/v1",
            "https://user@example.org/v1",
            "https://@example.org/v1",
            "https://example.org/v1?api_key=secret",
            "https://example.org/v1?",
            "https://example.org/v1#secret",
            "https://example.org/v1#",
            "https://example.org:wrong/v1",
            "https://example.org:70000/v1",
            "https://[::1/v1",
            "https://example.org/my path",
            "https://example.org\\v1",
            "https://example.org/\n",
            "\thttps://example.org/v1",
            "https://example.org/\u0000",
            "https://example.org/\u007f",
            "https://example.org/\u0085",
        ).forEach { url ->
            assertThrows("should refuse: $url", IllegalArgumentException::class.java) { normalizeBaseUrl(url) }
        }
    }

    @Test
    fun invalidModelsAreRefused() {
        listOf("model\n", "model\u007f", "model\u0085").forEach { model ->
            assertThrows(IllegalArgumentException::class.java) {
                validatedProviderConfig("https://example.org/v1", model, false)
            }
        }
    }

    @Test
    fun unknownProviderIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { apiProvider("missing") }
    }

    @Test
    fun placeholderKeysAreRecognised() {
        listOf("", "  ", "YOUR_API_KEY", "your  api   key", "填这里", "请在这里填写").forEach {
            assertTrue("placeholder: $it", isPlaceholderApiKey(it))
        }
        assertFalse(isPlaceholderApiKey("sk-real-looking-key"))
    }

    @Test
    fun secretsAreTrimmedAndValidated() {
        assertEquals("sk-abc", prepareSecretForWrite("  sk-abc\n"))
        assertNull(prepareSecretForWrite("   "))
        assertNull(prepareSecretForWrite("sk\u0000abc"))
        assertNull(prepareSecretForWrite("a".repeat(1281)))
        assertEquals(1280, prepareSecretForWrite("a".repeat(1280))?.length)
    }
}

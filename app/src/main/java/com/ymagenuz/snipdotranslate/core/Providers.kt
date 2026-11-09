package com.ymagenuz.snipdotranslate.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

const val DEFAULT_API_PROVIDER = "gptsapi"
const val DEEPSEEK_API_PROVIDER = "deepseek"
const val OPENROUTER_API_PROVIDER = "openrouter"
const val OPENAI_COMPATIBLE_API_PROVIDER = "openai_compatible"

private val URL_SHAPE = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/]*)(/.*)?$""", RegexOption.DOT_MATCHES_ALL)
private val AUTHORITY = Regex("""^(\[[0-9A-Fa-f:.]+\]|[^:\[\]@]+)(?::([0-9]*))?$""")
private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

private fun hasControlCharacters(value: String): Boolean = value.any { Character.getType(it) == Character.CONTROL.toInt() }

/**
 * Validates a provider address. Unlike the desktop app, plain `http://` is refused,
 * because on a phone it would send API keys across shared networks.
 * [allowLocalHttp] exists for debug builds talking to a local test server.
 */
fun normalizeBaseUrl(value: String, allowLocalHttp: Boolean = false): String {
    require(!hasControlCharacters(value)) { "API base URL must not contain control characters" }
    val normalized = value.pyStrip().trimEnd('/')
    require(normalized.none { it.isWhitespace() } && '\\' !in normalized) {
        "API base URL must not contain whitespace or backslashes"
    }
    require('?' !in normalized && '#' !in normalized) {
        "API base URL must not contain a query or fragment"
    }

    val shape = requireNotNull(URL_SHAPE.matchEntire(normalized)) { "API base URL is invalid" }
    val scheme = shape.groupValues[1].lowercase()
    val authority = requireNotNull(AUTHORITY.matchEntire(shape.groupValues[2])) {
        "API base URL must have a host and no credentials"
    }
    val host = authority.groupValues[1].lowercase()
    val port = authority.groupValues[2]
    require(port.isEmpty() || (port.length <= 5 && port.toInt() <= 65535)) { "API base URL has an invalid port" }

    val allowed = scheme == "https" || (scheme == "http" && allowLocalHttp && host in LOCAL_HOSTS)
    require(allowed) { "API base URL must use HTTPS" }
    return normalized
}

data class ApiProviderConfig(
    val baseUrl: String,
    val model: String,
    val supportsVision: Boolean,
    val disableThinking: Boolean = false,
)

/** Validates user-entered provider settings; throws [IllegalArgumentException] if unusable. */
fun validatedProviderConfig(
    baseUrl: String,
    model: String,
    supportsVision: Boolean,
    disableThinking: Boolean = false,
    allowLocalHttp: Boolean = false,
): ApiProviderConfig {
    require(!hasControlCharacters(model)) { "API model must not contain control characters" }
    return ApiProviderConfig(
        baseUrl = normalizeBaseUrl(baseUrl, allowLocalHttp),
        model = model.pyStrip(),
        supportsVision = supportsVision,
        disableThinking = disableThinking,
    )
}

data class ApiProviderSpec(
    val providerId: String,
    val displayName: String,
    val shortName: String,
    val baseUrl: String,
    val model: String,
    val supportsVision: Boolean,
    val disableThinking: Boolean = false,
    val baseUrlEditable: Boolean = false,
)

val API_PROVIDERS: List<ApiProviderSpec> = listOf(
    ApiProviderSpec(
        providerId = DEFAULT_API_PROVIDER,
        displayName = "GPTSAPI",
        shortName = "GPTSAPI",
        baseUrl = "https://api.gptsapi.net/v1",
        model = "gpt-5.4-nano",
        supportsVision = true,
    ),
    ApiProviderSpec(
        providerId = DEEPSEEK_API_PROVIDER,
        displayName = "DeepSeek (official API)",
        shortName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-v4-flash",
        supportsVision = false,
        disableThinking = true,
    ),
    ApiProviderSpec(
        providerId = OPENROUTER_API_PROVIDER,
        displayName = "OpenRouter",
        shortName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "openai/gpt-5.6-luna",
        supportsVision = true,
    ),
    ApiProviderSpec(
        providerId = OPENAI_COMPATIBLE_API_PROVIDER,
        displayName = "OpenAI-compatible (custom)",
        shortName = "OpenAI-compatible",
        baseUrl = "https://api.openai.com/v1",
        model = "",
        supportsVision = false,
        baseUrlEditable = true,
    ),
)

fun apiProvider(providerId: String, config: ApiProviderConfig? = null): ApiProviderSpec {
    val provider = requireNotNull(API_PROVIDERS.firstOrNull { it.providerId == providerId }) {
        "unsupported API provider"
    }
    return if (config == null) {
        provider
    } else {
        provider.copy(
            baseUrl = config.baseUrl,
            model = config.model,
            supportsVision = config.supportsVision,
            disableThinking = config.disableThinking,
        )
    }
}

fun providerDefaultConfig(providerId: String): ApiProviderConfig {
    val provider = apiProvider(providerId)
    return ApiProviderConfig(provider.baseUrl, provider.model, provider.supportsVision, provider.disableThinking)
}

data class ChatCompletionOptions(
    val model: String,
    val headers: Map<String, String>,
    val extraBody: JsonObject?,
)

fun chatCompletionOptions(provider: ApiProviderSpec, streaming: Boolean = false): ChatCompletionOptions {
    val extraBody = when {
        !provider.disableThinking -> null
        // OpenRouter uses its unified reasoning object and ignores DeepSeek's extension.
        provider.providerId == OPENROUTER_API_PROVIDER -> buildJsonObject {
            putJsonObject("reasoning") { put("effort", "none") }
        }
        else -> buildJsonObject {
            putJsonObject("thinking") { put("type", "disabled") }
        }
    }
    return ChatCompletionOptions(
        model = provider.model,
        headers = if (streaming) mapOf("Accept" to "text/event-stream") else emptyMap(),
        extraBody = extraBody,
    )
}

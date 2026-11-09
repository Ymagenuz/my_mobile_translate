package com.ymagenuz.snipdotranslate.core

import java.text.Normalizer
import java.util.Locale

private const val MAX_SECRET_UTF16_BYTES = 2560

private val KNOWN_PLACEHOLDERS = setOf(
    "",
    "your_api_key",
    "your-api-key",
    "your api key",
    "填这里",
    "填 这里",
    "请填这里",
    "请填 这里",
    "在这里填写",
    "请在这里填写",
)

fun isPlaceholderApiKey(value: String): Boolean {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).pyStrip().lowercase(Locale.ROOT)
    return WHITESPACE_RUN_FOR_KEYS.replace(normalized, " ") in KNOWN_PLACEHOLDERS
}

private val WHITESPACE_RUN_FOR_KEYS = Regex("$WS+")

/** Returns the key to store, or null if it can't be a usable API key. */
fun prepareSecretForWrite(secret: String): String? {
    val normalized = secret.pyStrip()
    if (normalized.isEmpty() || normalized.length * 2 > MAX_SECRET_UTF16_BYTES) return null
    if (normalized.any { Character.getType(it) == Character.CONTROL.toInt() }) return null
    return normalized
}

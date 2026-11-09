package com.ymagenuz.snipdotranslate.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun isUnusableCharacter(character: Char): Boolean {
    val type = Character.getType(character)
    return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
}

/** Extracts usable model IDs from an OpenAI-compatible `GET /models` response. */
fun parseModelIds(payload: JsonElement): List<String> {
    val data = (payload as? JsonObject)?.get("data")
    if (data != null && data !is JsonNull && data !is JsonArray) {
        throw IllegalArgumentException(
            "The API returned an invalid model list. Enter a model ID manually or check the API base URL.",
        )
    }

    val models = (data as? JsonArray).orEmpty().mapNotNull { entry ->
        val id = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return@mapNotNull null
        id.takeUnless { it.any(::isUnusableCharacter) }?.pyStrip()?.takeIf { it.isNotEmpty() }
    }
    require(models.isNotEmpty()) {
        "The API returned no available models. Enter a model ID manually or check the API base URL."
    }
    return models.distinct().sorted()
}

package com.ymagenuz.snipdotranslate.core

import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

const val REPOSITORY_URL = "https://github.com/Ymagenuz/my_mobile_translate"
const val LATEST_RELEASE_API = "https://api.github.com/repos/Ymagenuz/my_mobile_translate/releases/latest"
const val UPDATE_APK = "SnipDoTranslate.apk"
const val UPDATE_CHECKSUM = "$UPDATE_APK.sha256"
const val UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

private const val MAX_APK_BYTES = 512L * 1024L * 1024L
private const val MAX_CHECKSUM_BYTES = 4096L
private const val MAX_NOTES_CHARS = 16_000

private val VERSION = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
private val PUBLISHED_AT = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})T[0-9:.]+Z")

// Release notes end with checksums and build details that only matter on GitHub.
private val DOWNLOAD_SECTION = Regex(
    """(?:\A|(?<=\n))#{1,6}[ \t]+Download and verification(?![\p{L}\p{N}_]).*""",
    RegexOption.DOT_MATCHES_ALL,
)

data class ReleaseInfo(
    val version: String,
    val url: String,
    val notes: String = "",
    val published: String = "",
)

private fun versionParts(version: String): List<BigInteger> {
    require(version.length <= 64) { "Invalid release version" }
    val match = requireNotNull(VERSION.matchEntire(version)) { "Invalid release version" }
    return match.groupValues.drop(1).map { it.toBigInteger() }
}

private fun isAbove(left: List<BigInteger>, right: List<BigInteger>): Boolean {
    val difference = left.zip(right).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
    return difference > 0
}

fun isNewerVersion(version: String, currentVersion: String): Boolean = try {
    isAbove(versionParts(version), versionParts(currentVersion))
} catch (_: IllegalArgumentException) {
    false
}

fun releaseNotes(body: JsonElement?): String {
    val text = (body as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
    val notes = DOWNLOAD_SECTION.replace(text.replace("\r\n", "\n"), "").pyStrip()
    return if (notes.length > MAX_NOTES_CHARS) {
        notes.take(MAX_NOTES_CHARS).trimEnd() + "\n\n…"
    } else {
        notes
    }
}

private fun JsonElement?.strictBoolean(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && (it.content == "true" || it.content == "false") }
        ?.content?.toBoolean()

private fun JsonElement?.plainString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.strictLong(): Long? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/**
 * Returns a newer stable release with an uploaded APK and checksum, or null.
 * Malformed metadata throws [IllegalArgumentException]. Links are built from the
 * validated tag rather than trusting URLs supplied by the server.
 */
fun parseRelease(payload: JsonElement, currentVersion: String): ReleaseInfo? {
    val current = versionParts(currentVersion)
    require(payload is JsonObject) { "Invalid release metadata" }
    val draft = requireNotNull(payload["draft"].strictBoolean()) { "Invalid release metadata" }
    val prerelease = requireNotNull(payload["prerelease"].strictBoolean()) { "Invalid release metadata" }
    if (draft || prerelease) return null

    val tag = requireNotNull(payload["tag_name"].plainString()) { "Invalid release version" }
    val version = versionParts(tag)
    if (!isAbove(version, current)) return null

    val assets = requireNotNull(payload["assets"] as? JsonArray) { "Invalid release assets" }
    for ((name, maximum) in listOf(UPDATE_APK to MAX_APK_BYTES, UPDATE_CHECKSUM to MAX_CHECKSUM_BYTES)) {
        val asset = assets.filterIsInstance<JsonObject>().filter { it["name"].plainString() == name }
            .singleOrNull() ?: return null
        val size = asset["size"].strictLong() ?: return null
        val usable = asset["state"].plainString() == "uploaded" &&
            size in 1..maximum &&
            asset["browser_download_url"].plainString() == "$REPOSITORY_URL/releases/download/$tag/$name"
        if (!usable) return null
    }

    return ReleaseInfo(
        version = version.joinToString("."),
        url = "$REPOSITORY_URL/releases/tag/$tag",
        notes = releaseNotes(payload["body"]),
        published = payload["published_at"].plainString()
            ?.let { PUBLISHED_AT.matchEntire(it)?.groupValues?.get(1) }
            .orEmpty(),
    )
}

/** Automatic checks run at most once per interval, and always if the clock went backwards. */
fun isUpdateCheckDue(lastCheckMs: Long?, nowMs: Long): Boolean =
    lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= UPDATE_CHECK_INTERVAL_MS

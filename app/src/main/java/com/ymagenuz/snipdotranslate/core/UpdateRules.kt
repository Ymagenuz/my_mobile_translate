package com.ymagenuz.snipdotranslate.core

import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
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

const val MAX_UPDATE_REDIRECTS = 5

// GitHub's documented release-asset hosts; never arbitrary subdomains.
private val UPDATE_ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
private val UPDATE_CHECKSUM_LINE = Regex("([0-9a-fA-F]{64})  ${Regex.escape(UPDATE_APK)}(?:\\r?\\n)?")

data class ReleaseInfo(
    val version: String,
    val url: String,
    val notes: String = "",
    val published: String = "",
    val apkUrl: String = "",
    val apkSize: Long = 0,
    val checksumUrl: String = "",
    val checksumSize: Long = 0,
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
    val sizes = mutableMapOf<String, Long>()
    for ((name, maximum) in listOf(UPDATE_APK to MAX_APK_BYTES, UPDATE_CHECKSUM to MAX_CHECKSUM_BYTES)) {
        val asset = assets.filterIsInstance<JsonObject>().filter { it["name"].plainString() == name }
            .singleOrNull() ?: return null
        val size = asset["size"].strictLong() ?: return null
        val usable = asset["state"].plainString() == "uploaded" &&
            size in 1..maximum &&
            asset["browser_download_url"].plainString() == assetUrl(tag, name)
        if (!usable) return null
        sizes[name] = size
    }

    return ReleaseInfo(
        version = version.joinToString("."),
        url = "$REPOSITORY_URL/releases/tag/$tag",
        notes = releaseNotes(payload["body"]),
        published = payload["published_at"].plainString()
            ?.let { PUBLISHED_AT.matchEntire(it)?.groupValues?.get(1) }
            .orEmpty(),
        apkUrl = assetUrl(tag, UPDATE_APK),
        apkSize = sizes.getValue(UPDATE_APK),
        checksumUrl = assetUrl(tag, UPDATE_CHECKSUM),
        checksumSize = sizes.getValue(UPDATE_CHECKSUM),
    )
}

private fun assetUrl(tag: String, name: String) = "$REPOSITORY_URL/releases/download/$tag/$name"

/** Whether [release] points at this project's own assets for its version, as parsed above. */
fun isDownloadableRelease(release: ReleaseInfo): Boolean {
    val prefix = "$REPOSITORY_URL/releases/tag/"
    if (!release.url.startsWith(prefix)) return false
    val tag = release.url.removePrefix(prefix)
    val sameVersion = try {
        versionParts(tag) == versionParts(release.version)
    } catch (_: IllegalArgumentException) {
        false
    }
    return sameVersion &&
        release.apkSize in 1..MAX_APK_BYTES &&
        release.checksumSize in 1..MAX_CHECKSUM_BYTES &&
        release.apkUrl == assetUrl(tag, UPDATE_APK) &&
        release.checksumUrl == assetUrl(tag, UPDATE_CHECKSUM)
}

/**
 * Whether a download may go to [url]: the exact GitHub link it started from, or one of
 * GitHub's asset hosts that link redirects to. HTTPS only, with nothing unusual in it.
 */
fun isAllowedUpdateUrl(url: String, initialUrl: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return false
    }
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return false
    return when (uri.host) {
        "github.com" -> url == initialUrl
        in UPDATE_ASSET_HOSTS -> true
        else -> false
    }
}

/** The lowercase SHA-256 from a checksum file written as "<hash>  SnipDoTranslate.apk", or null. */
fun parseUpdateChecksum(body: ByteArray): String? =
    UPDATE_CHECKSUM_LINE.matchEntire(String(body, Charsets.US_ASCII))?.groupValues?.get(1)?.lowercase()

/** Automatic checks run at most once per interval, and always if the clock went backwards. */
fun isUpdateCheckDue(lastCheckMs: Long?, nowMs: Long): Boolean =
    lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= UPDATE_CHECK_INTERVAL_MS

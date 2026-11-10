package com.ymagenuz.snipdotranslate.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private fun asset(
        name: String,
        tag: String = "v13.0.0",
        size: JsonElement = JsonPrimitive(1024),
        state: String = "uploaded",
        url: String = "$REPOSITORY_URL/releases/download/$tag/$name",
    ): JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "state" to JsonPrimitive(state),
            "size" to size,
            "browser_download_url" to JsonPrimitive(url),
        ),
    )

    private fun release(
        tag: JsonElement = JsonPrimitive("v13.0.0"),
        assets: List<JsonElement>? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject {
        val tagText = (tag as? JsonPrimitive)?.content.orEmpty()
        return JsonObject(
            mapOf(
                "tag_name" to tag,
                "draft" to JsonPrimitive(false),
                "prerelease" to JsonPrimitive(false),
                "html_url" to JsonPrimitive("https://untrusted.example/release"),
                "assets" to JsonArray(assets ?: listOf(asset(UPDATE_APK, tagText), asset(UPDATE_CHECKSUM, tagText, JsonPrimitive(86)))),
            ) + extra,
        )
    }

    private fun parse(payload: JsonElement, current: String = "12.0.0") = parseRelease(payload, current)

    @Test
    fun comparesNumericallyAndBuildsTrustedUrl() {
        val result = parse(release(JsonPrimitive("v1.10.0")), "1.9.9")
        val download = "$REPOSITORY_URL/releases/download/v1.10.0"
        val expected = ReleaseInfo(
            version = "1.10.0",
            url = "$REPOSITORY_URL/releases/tag/v1.10.0",
            apkUrl = "$download/$UPDATE_APK",
            apkSize = 1024,
            checksumUrl = "$download/$UPDATE_CHECKSUM",
            checksumSize = 86,
        )
        assertEquals(expected, result)
        assertTrue(isDownloadableRelease(requireNotNull(result)))
    }

    @Test
    fun onlyThisProjectsAssetsForTheSameVersionAreDownloadable() {
        val good = requireNotNull(parse(release()))
        assertTrue(isDownloadableRelease(good))
        listOf(
            good.copy(version = "13.0.1"),
            good.copy(url = "https://untrusted.example/releases/tag/v13.0.0"),
            good.copy(apkUrl = "https://untrusted.example/$UPDATE_APK"),
            good.copy(checksumUrl = "$REPOSITORY_URL/releases/download/v12.0.0/$UPDATE_CHECKSUM"),
            good.copy(apkSize = 0),
            good.copy(apkSize = 512L * 1024L * 1024L + 1L),
            good.copy(checksumSize = 4097),
        ).forEach { assertFalse("release $it", isDownloadableRelease(it)) }
    }

    @Test
    fun downloadsGoOnlyToTheReleaseLinkAndGitHubAssetHosts() {
        val initial = "$REPOSITORY_URL/releases/download/v13.0.0/$UPDATE_APK"
        assertTrue(isAllowedUpdateUrl(initial, initial))
        assertTrue(isAllowedUpdateUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x", initial))
        assertTrue(isAllowedUpdateUrl("https://objects.githubusercontent.com/a/b", initial))
        listOf(
            "http://release-assets.githubusercontent.com/a",
            "https://evil.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com:8443/a",
            "https://user@release-assets.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com/a#part",
            "https://github.com/Ymagenuz/my_mobile_translate/releases/download/v12.0.0/$UPDATE_APK",
            "https://untrusted.example/$UPDATE_APK",
            "not a url",
        ).forEach { assertFalse("url $it", isAllowedUpdateUrl(it, initial)) }
    }

    @Test
    fun checksumFilesMustNameTheApk() {
        val hash = "AB".repeat(32)
        assertEquals(hash.lowercase(), parseUpdateChecksum("$hash  $UPDATE_APK".toByteArray()))
        assertEquals(hash.lowercase(), parseUpdateChecksum("$hash  $UPDATE_APK\r\n".toByteArray()))
        listOf(
            hash,
            "$hash $UPDATE_APK",
            "$hash  other.apk",
            "${hash.drop(2)}  $UPDATE_APK",
            "${"g".repeat(64)}  $UPDATE_APK",
            "$hash  $UPDATE_APK\n\n",
        ).forEach { assertNull("checksum $it", parseUpdateChecksum(it.toByteArray())) }
    }

    @Test
    fun acceptsVersionsWithAndWithoutPrefix() {
        listOf("1.2.3", "v1.2.3").forEach { assertEquals("1.2.3", parse(release(JsonPrimitive(it)), "v1.2.2")?.version) }
    }

    @Test
    fun equalOrOlderReleaseIsNoUpdate() {
        listOf("1.9.9", "v1.10.0", "0.99.999").forEach { assertNull(parse(release(JsonPrimitive(it)), "1.10.0")) }
    }

    @Test
    fun invalidTagsAreRejected() {
        val tags: List<JsonElement> = listOf(
            "v1.2", "1.2.3.4", "1.02.3", "1.2.3-rc.1", "1.2.3+metadata", "1.2.3\n",
            "../../redirect", "https://untrusted.example", "v１.2.3",
        ).map { JsonPrimitive(it) } + listOf(JsonNull, JsonPrimitive(1300))
        tags.forEach { tag ->
            assertThrows("tag $tag", IllegalArgumentException::class.java) { parse(release(tag, assets = emptyList()), "1.0.0") }
        }
    }

    @Test
    fun invalidMetadataIsRejected() {
        val payloads: List<JsonElement> = listOf(
            JsonNull,
            JsonArray(emptyList()),
            JsonObject(emptyMap()),
            JsonObject(mapOf("draft" to JsonPrimitive("false"), "prerelease" to JsonPrimitive(false))),
        )
        payloads.forEach { assertThrows(IllegalArgumentException::class.java) { parse(it) } }
    }

    @Test
    fun draftsAndPrereleasesAreIgnored() {
        listOf("draft", "prerelease").forEach { field ->
            assertNull(parse(release(extra = mapOf(field to JsonPrimitive(true)))))
        }
    }

    @Test
    fun onlyFinishedMatchingApkUploadsAreUsable() {
        val checksum = asset(UPDATE_CHECKSUM, size = JsonPrimitive(86))
        val base = "https://github.com/Ymagenuz/my_mobile_translate/releases/download"
        listOf(
            asset("other.apk"),
            asset("$UPDATE_APK.zip"),
            asset(UPDATE_APK, state = "new"),
            asset(UPDATE_APK, size = JsonPrimitive(0)),
            asset(UPDATE_APK, size = JsonPrimitive(-1)),
            asset(UPDATE_APK, size = JsonPrimitive("1024")),
            asset(UPDATE_APK, size = JsonPrimitive(true)),
            asset(UPDATE_APK, size = JsonPrimitive(512L * 1024L * 1024L + 1L)),
            asset(UPDATE_APK, url = "https://untrusted.example/$UPDATE_APK"),
            asset(UPDATE_APK, url = "http://github.com/Ymagenuz/my_mobile_translate/releases/download/v13.0.0/$UPDATE_APK"),
            asset(UPDATE_APK, url = "https://github.com/other/my_mobile_translate/releases/download/v13.0.0/$UPDATE_APK"),
            asset(UPDATE_APK, url = "$base/v12.0.0/$UPDATE_APK"),
        ).forEach { apk -> assertNull("asset $apk", parse(release(assets = listOf(apk, checksum)))) }
    }

    @Test
    fun finishedChecksumFromTheSameReleaseIsRequired() {
        val apk = asset(UPDATE_APK)
        listOf(
            asset("checksums.txt"),
            asset(UPDATE_CHECKSUM, state = "new"),
            asset(UPDATE_CHECKSUM, size = JsonPrimitive(0)),
            asset(UPDATE_CHECKSUM, size = JsonPrimitive(true)),
            asset(UPDATE_CHECKSUM, size = JsonPrimitive("86")),
            asset(UPDATE_CHECKSUM, size = JsonPrimitive(4097)),
            asset(UPDATE_CHECKSUM, url = "https://untrusted.example/$UPDATE_CHECKSUM"),
            asset(UPDATE_CHECKSUM, url = "$REPOSITORY_URL/releases/download/v12.0.0/$UPDATE_CHECKSUM"),
        ).forEach { checksum -> assertNull("asset $checksum", parse(release(assets = listOf(apk, checksum)))) }
    }

    @Test
    fun missingOrDuplicateAssetsHaveNoUpdate() {
        val apk = asset(UPDATE_APK)
        val checksum = asset(UPDATE_CHECKSUM, size = JsonPrimitive(86))
        assertNull(parse(release(assets = emptyList())))
        assertNull(parse(release(assets = listOf(apk))))
        assertNull(parse(release(assets = listOf(checksum))))
        assertNull(parse(release(assets = listOf(apk, apk, checksum))))
        assertNull(parse(release(assets = listOf(apk, checksum, checksum))))
    }

    @Test
    fun unrelatedAssetsDoNotHideAValidApk() {
        val assets = listOf(
            JsonNull,
            JsonPrimitive("invalid"),
            JsonObject(mapOf("name" to JsonPrimitive("source.zip"))),
            asset(UPDATE_APK),
            asset(UPDATE_CHECKSUM, size = JsonPrimitive(86)),
        )
        assertEquals("13.0.0", parse(release(assets = assets))?.version)
    }

    @Test
    fun notesKeepChangesAndDropDownloadDetails() {
        val body = "## What's changed\r\n\r\n- Faster.\r\n\r\n## Upgrading\r\n\r\nUpdate in the app.\r\n\r\n" +
            "## Download and verification\r\n\r\n```text\r\nabc123\r\n```\r\n"
        val notes = parse(release(extra = mapOf("body" to JsonPrimitive(body))))?.notes
        assertEquals("## What's changed\n\n- Faster.\n\n## Upgrading\n\nUpdate in the app.", notes)
    }

    @Test
    fun unusableNotesDoNotHideTheRelease() {
        listOf(JsonNull, JsonPrimitive(12), JsonArray(listOf(JsonPrimitive("notes")))).forEach { body ->
            val result = parse(release(extra = mapOf("body" to body)))
            assertEquals("13.0.0", result?.version)
            assertEquals("", result?.notes)
        }
    }

    @Test
    fun releaseDateIsKeptOnlyWhenWellFormed() {
        listOf<Pair<JsonElement, String>>(
            JsonPrimitive("2026-11-20T08:15:00Z") to "2026-11-20",
            JsonPrimitive("2026-11-20") to "",
            JsonPrimitive("yesterday") to "",
            JsonNull to "",
            JsonPrimitive(20261120) to "",
        ).forEach { (value, date) ->
            assertEquals(date, parse(release(extra = mapOf("published_at" to value)))?.published)
        }
    }

    @Test
    fun longNotesAreShortened() {
        val notes = releaseNotes(JsonPrimitive("x".repeat(16_050)))
        assertTrue(notes.length <= 16_003)
        assertTrue(notes.endsWith("\n\n…"))
    }

    @Test
    fun newerVersionCheckToleratesBadInput() {
        assertTrue(isNewerVersion("1.10.0", "1.9.9"))
        assertFalse(isNewerVersion("1.9.9", "1.10.0"))
        assertFalse(isNewerVersion("nonsense", "1.0.0"))
    }

    @Test
    fun automaticChecksRunAtMostEverySixHours() {
        val hour = 60L * 60L * 1000L
        assertTrue(isUpdateCheckDue(null, 1_000L))
        assertFalse(isUpdateCheckDue(0L, 5 * hour))
        assertTrue(isUpdateCheckDue(0L, 6 * hour))
        assertTrue(isUpdateCheckDue(10 * hour, 5 * hour))
    }
}

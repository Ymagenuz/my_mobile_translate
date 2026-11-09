package com.ymagenuz.snipdotranslate.update

import com.ymagenuz.snipdotranslate.core.LATEST_RELEASE_API
import com.ymagenuz.snipdotranslate.core.ReleaseInfo
import com.ymagenuz.snipdotranslate.core.parseRelease
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TIMEOUT_SECONDS = 10L
private const val MAX_RESPONSE_BYTES = 256L * 1024L
private const val HTTP_NOT_FOUND = 404

/**
 * Asks this project's public GitHub releases whether a newer version exists. It sends
 * no keys, text or history; the only thing it reveals is the app version.
 */
class UpdateChecker(
    http: OkHttpClient,
    private val currentVersion: String,
    private val url: String = LATEST_RELEASE_API,
) {
    private val client = http.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** Returns the newer release, or null if there is none. Throws [IOException] on failure. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SnipDoTranslateAndroid/$currentVersion")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        client.newCall(request).execute().use { response ->
            // A repository with no releases yet answers 404, which isn't a problem.
            if (response.code == HTTP_NOT_FOUND) return@withContext null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            val source = response.body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("The response was too large.")
            try {
                parseRelease(Json.parseToJsonElement(source.buffer.readUtf8()), currentVersion)
            } catch (_: SerializationException) {
                throw IOException("The response couldn't be read.")
            } catch (_: IllegalArgumentException) {
                throw IOException("The response couldn't be read.")
            }
        }
    }
}

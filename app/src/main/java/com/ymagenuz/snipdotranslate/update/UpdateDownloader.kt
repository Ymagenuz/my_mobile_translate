package com.ymagenuz.snipdotranslate.update

import com.ymagenuz.snipdotranslate.core.MAX_UPDATE_REDIRECTS
import com.ymagenuz.snipdotranslate.core.ReleaseInfo
import com.ymagenuz.snipdotranslate.core.isAllowedUpdateUrl
import com.ymagenuz.snipdotranslate.core.parseUpdateChecksum
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val INACTIVITY_SECONDS = 30L
private const val OVERALL_MS = 30L * 60L * 1000L
private const val CHUNK_BYTES = 64 * 1024
private val REDIRECTS = setOf(301, 302, 303, 307, 308)

/**
 * Downloads a release's APK and checks it against the release's checksum file, with the
 * same limits as the desktop app: only GitHub's own links, exact sizes, no compression.
 * [isAllowed] decides where requests may go; tests replace it to use a local server.
 */
class UpdateDownloader(
    http: OkHttpClient,
    private val currentVersion: String,
    private val isAllowed: (url: String, initialUrl: String) -> Boolean = ::isAllowedUpdateUrl,
) {
    private val client = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(INACTIVITY_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .cache(null)
        .build()

    /**
     * Saves the verified APK as [destination], reporting bytes received of the total.
     * Throws [IOException] if anything doesn't match. Each attempt writes its own partial
     * file, so a cancelled one can't disturb the next.
     */
    suspend fun download(release: ReleaseInfo, destination: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(OVERALL_MS) { fetchVerified(release, destination, onProgress) }
            } catch (_: TimeoutCancellationException) {
                throw IOException("The download took too long.")
            }
        }
    }

    private suspend fun fetchVerified(release: ReleaseInfo, destination: File, onProgress: (Long, Long) -> Unit) {
        val checksum = fetch(release.checksumUrl, release.checksumSize) { it.readBytes() }
        val expected = parseUpdateChecksum(checksum) ?: throw IOException("The checksum file couldn't be read.")

        val folder = destination.absoluteFile.parentFile ?: throw IOException("No folder for the download.")
        folder.mkdirs()
        val partial = File(folder, "${destination.name}.${System.nanoTime()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val context = currentCoroutineContext()
            fetch(release.apkUrl, release.apkSize) { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var received = 0L
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(received, release.apkSize)
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException("The download didn't match its checksum.")
            destination.delete()
            if (!partial.renameTo(destination)) throw IOException("The download couldn't be saved.")
        } finally {
            partial.delete()
        }
    }

    /**
     * Follows redirects by hand, checking each address, and passes [read] a stream that is
     * exactly [size] bytes long or fails.
     */
    private suspend fun <T : Any> fetch(initialUrl: String, size: Long, read: (InputStream) -> T): T {
        var url = initialUrl
        repeat(MAX_UPDATE_REDIRECTS + 1) {
            if (!isAllowed(url, initialUrl)) throw IOException("The download address wasn't allowed.")
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                // Also stops OkHttp from asking for, and quietly unpacking, gzip.
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "SnipDoTranslateAndroid/$currentVersion")
                .build()
            await(client.newCall(request)).use { response ->
                if (response.code in REDIRECTS) {
                    val location = response.header("Location") ?: throw IOException("A redirect had no address.")
                    url = response.request.url.resolve(location)?.toString()
                        ?: throw IOException("A redirect address couldn't be read.")
                } else {
                    if (response.code != 200) throw IOException("HTTP ${response.code}")
                    val length = response.body.contentLength()
                    if (length != -1L && length != size) throw IOException("The file was a different size than expected.")
                    val encoding = response.header("Content-Encoding")?.trim()?.lowercase().orEmpty()
                    if (encoding != "" && encoding != "identity") throw IOException("The file came compressed.")
                    return ExactLengthStream(response.body.byteStream(), size).use(read)
                }
            }
        }
        throw IOException("The download was redirected too many times.")
    }
}

/** Waits for the response headers; cancelling the coroutine cancels the request. */
private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}

/** Fails if [source] gives more than [size] bytes, or ends before [size]. */
private class ExactLengthStream(private val source: InputStream, private val size: Long) : InputStream() {
    private var received = 0L

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = source.read(buffer, offset, length)
        if (count < 0) {
            if (received != size) throw IOException("The file ended early.")
            return -1
        }
        received += count
        if (received > size) throw IOException("The file was larger than expected.")
        return count
    }

    override fun close() = source.close()
}

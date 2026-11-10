package com.ymagenuz.snipdotranslate.update

import com.ymagenuz.snipdotranslate.api.createHttpClient
import com.ymagenuz.snipdotranslate.core.ReleaseInfo
import com.ymagenuz.snipdotranslate.core.UPDATE_APK
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val checksum = "$hash  $UPDATE_APK"
    private lateinit var destination: File

    @Before
    fun start() {
        server.start()
        destination = File(folder.root, "updates/$UPDATE_APK")
    }

    @After
    fun stop() {
        server.shutdown()
    }

    // Only this test server may be reached, in place of GitHub's hosts.
    private fun downloader() = UpdateDownloader(createHttpClient(), "1.0.0") { url, _ ->
        url.startsWith(server.url("/").toString())
    }

    private fun release(apkSize: Long = apk.size.toLong(), checksumSize: Long = checksum.length.toLong()) = ReleaseInfo(
        version = "2.0.0",
        url = "",
        apkUrl = server.url("/apk").toString(),
        apkSize = apkSize,
        checksumUrl = server.url("/checksum").toString(),
        checksumSize = checksumSize,
    )

    private fun serve(body: ByteArray) = MockResponse().setBody(Buffer().write(body))

    private fun download(release: ReleaseInfo = release(), progress: MutableList<Long> = mutableListOf()) =
        runBlocking { downloader().download(release, destination) { received, _ -> progress += received } }

    private fun leftovers() = destination.parentFile?.listFiles()?.map { it.name }.orEmpty()

    @Test
    fun aMatchingDownloadIsSaved() {
        server.enqueue(MockResponse().setBody(checksum))
        server.enqueue(serve(apk))
        val progress = mutableListOf<Long>()
        download(progress = progress)

        assertArrayEquals(apk, destination.readBytes())
        assertEquals(apk.size.toLong(), progress.last())
        assertEquals(listOf(UPDATE_APK), leftovers())
        val request = server.takeRequest()
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals("SnipDoTranslateAndroid/1.0.0", request.getHeader("User-Agent"))
    }

    @Test
    fun aDownloadThatDoesntMatchItsChecksumIsDiscarded() {
        server.enqueue(MockResponse().setBody("${"0".repeat(64)}  $UPDATE_APK"))
        server.enqueue(serve(apk))
        assertThrows(IOException::class.java) { download() }
        assertFalse(destination.exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun anUnreadableChecksumStopsBeforeTheApk() {
        server.enqueue(MockResponse().setBody("x".repeat(checksum.length)))
        assertThrows(IOException::class.java) { download() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFileOfTheWrongSizeIsRefused() {
        server.enqueue(MockResponse().setBody(checksum))
        server.enqueue(serve(apk))
        assertThrows(IOException::class.java) { download(release(apkSize = apk.size + 1L)) }
        assertFalse(destination.exists())
    }

    @Test
    fun compressedResponsesAreRefused() {
        server.enqueue(MockResponse().setBody(checksum))
        server.enqueue(serve(apk).setHeader("Content-Encoding", "gzip"))
        assertThrows(IOException::class.java) { download() }
    }

    @Test
    fun allowedRedirectsAreFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/checksum-file")))
        server.enqueue(MockResponse().setBody(checksum))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/apk-file"))
        server.enqueue(serve(apk))
        download()
        assertArrayEquals(apk, destination.readBytes())
    }

    @Test
    fun redirectsElsewhereAreRefused() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.example/checksum"))
        assertThrows(IOException::class.java) { download() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun endlessRedirectsStop() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/again"))) }
        assertThrows(IOException::class.java) { download() }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun serverErrorsAreReported() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals("HTTP 404", assertThrows(IOException::class.java) { download() }.message)
    }
}

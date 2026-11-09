package com.ymagenuz.snipdotranslate.update

import com.ymagenuz.snipdotranslate.api.createHttpClient
import com.ymagenuz.snipdotranslate.core.REPOSITORY_URL
import com.ymagenuz.snipdotranslate.core.UPDATE_APK
import com.ymagenuz.snipdotranslate.core.UPDATE_CHECKSUM
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private val server = MockWebServer()
    private lateinit var checker: UpdateChecker

    @Before
    fun start() {
        server.start()
        checker = UpdateChecker(createHttpClient(), "1.0.0", server.url("/latest").toString())
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun asset(name: String) =
        """{"name":"$name","state":"uploaded","size":100,"browser_download_url":"$REPOSITORY_URL/releases/download/v2.0.0/$name"}"""

    private val release =
        """{"tag_name":"v2.0.0","draft":false,"prerelease":false,"assets":[${asset(UPDATE_APK)},${asset(UPDATE_CHECKSUM)}]}"""

    private fun check() = runBlocking { checker.check() }

    @Test
    fun aNewerReleaseIsReported() {
        server.enqueue(MockResponse().setBody(release))
        assertEquals("2.0.0", check()?.version)
    }

    @Test
    fun theRequestCarriesOnlyTheAppVersion() {
        server.enqueue(MockResponse().setBody(release))
        check()

        val request = server.takeRequest()
        assertEquals("SnipDoTranslateAndroid/1.0.0", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun aRepositoryWithoutReleasesIsNotAnError() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(check())
    }

    @Test
    fun serverErrorsAreReported() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("HTTP 500", assertThrows(IOException::class.java) { check() }.message)
    }

    @Test
    fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        assertThrows(IOException::class.java) { check() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unreadableResponsesAreReported() {
        server.enqueue(MockResponse().setBody("not json"))
        assertThrows(IOException::class.java) { check() }
        server.enqueue(MockResponse().setBody("{}"))
        assertThrows(IOException::class.java) { check() }
    }

    @Test
    fun oversizedResponsesAreRefused() {
        server.enqueue(MockResponse().setBody("x".repeat(300_000)))
        assertEquals("The response was too large.", assertThrows(IOException::class.java) { check() }.message)
    }
}

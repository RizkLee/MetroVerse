/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class DirectMediaRedirectInterceptorTest {
    @Test
    fun `follows trusted googlevideo and youtube redirects and delivers final response`() {
        val requestsSeen = mutableListOf<String>()
        val request = Request.Builder()
            .url("https://rr1---sn-abc.googlevideo.com/videoplayback?expire=999")
            .build()

        val response = DirectMediaRedirectInterceptor.intercept(
            FakeChain(request) { req ->
                requestsSeen += req.url.toString()
                when (requestsSeen.size) {
                    1 -> responseFor(req, 302, "https://rr2---sn-xyz.googlevideo.com/videoplayback?expire=999")
                    2 -> responseFor(req, 307, "https://music.youtube.com/videoplayback?expire=999")
                    else -> responseFor(req, 200, null)
                }
            },
        )

        assertEquals(200, response.code)
        assertEquals(3, requestsSeen.size)
        response.close()
    }

    @Test
    fun `rejects untrusted redirect target even when initial url is trusted`() {
        var hop = 0
        val request = Request.Builder()
            .url("https://rr1---sn-abc.googlevideo.com/videoplayback")
            .build()

        var thrown: IOException? = null
        try {
            DirectMediaRedirectInterceptor.intercept(
                FakeChain(request) { req ->
                    hop++
                    responseFor(req, 302, "https://evil.example.com/videoplayback")
                },
            )
        } catch (e: IOException) {
            thrown = e
        }

        assertEquals(1, hop)
        assertTrue(thrown != null)
    }

    @Test
    fun `rejects non-https custom ports userinfo and non-videoplayback paths`() {
        val rejected = listOf(
            "http://rr1.googlevideo.com/videoplayback",
            "https://rr1.googlevideo.com:8443/videoplayback",
            "https://user:pass@rr1.googlevideo.com/videoplayback",
            "https://rr1.googlevideo.com/watch",
            "https://podcasts.example.com/audio.mp3",
            "https://notgooglevideo.com/videoplayback",
        )
        for (url in rejected) {
            val request = Request.Builder().url(url).build()
            assertFalse("Expected endpoint to be rejected: $url", isTrustedDirectMediaEndpoint(request.url))
            var failed = false
            try {
                DirectMediaRedirectInterceptor.intercept(FakeChain(request) { req -> responseFor(req, 200, null) })
            } catch (_: IOException) {
                failed = true
            }
            assertTrue("Expected IOException for $url", failed)
        }
    }

    @Test
    fun `fails when redirect hops exceed limit`() {
        val request = Request.Builder()
            .url("https://rr1---sn-abc.googlevideo.com/videoplayback")
            .build()
        var thrown: IOException? = null
        try {
            DirectMediaRedirectInterceptor.intercept(
                FakeChain(request) { req ->
                    responseFor(req, 302, "https://rr2---sn-abc.googlevideo.com/videoplayback")
                },
            )
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue(thrown != null)
    }

    private fun responseFor(request: Request, code: Int, location: String?): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply {
                if (location != null) header("Location", location)
            }
            .body("".toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }

    private class FakeChain(
        private val initialRequest: Request,
        private val handler: (Request) -> Response,
    ) : Interceptor.Chain {
        override fun request(): Request = initialRequest
        override fun proceed(request: Request): Response = handler(request)
        override fun connection(): Connection? = null
        override fun call(): Call = error("unused")
        override fun connectTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 1000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 1000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}

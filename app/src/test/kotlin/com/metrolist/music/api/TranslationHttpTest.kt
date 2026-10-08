package com.metrolist.music.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranslationHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
    }

    @Test
    fun `success closes the body so the connection can be reused`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("first").build())
        server.enqueue(MockResponse.Builder().body("second").build())

        assertEquals("first", withTimeout(5_000) { newCall().awaitTranslationBody() })
        assertEquals("second", withTimeout(5_000) { newCall().awaitTranslationBody() })

        val first = server.takeRequest(5, TimeUnit.SECONDS)
        val second = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.connectionIndex, second!!.connectionIndex)
    }

    @Test
    fun `HTTP failure preserves retry metadata and closes its unread body`() = runBlocking {
        val released = CompletableDeferred<Unit>()
        client = client.newBuilder().eventListener(object : EventListener() {
            override fun callEnd(call: Call) {
                released.complete(Unit)
            }
        }).build()
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "45")
                .body("Provider content must not appear in the error")
                .build(),
        )

        val error = try {
            withTimeout(5_000) { newCall().awaitTranslationBody() }
            throw AssertionError("Expected HTTP failure")
        } catch (e: TranslationRequestException) {
            e
        }

        assertEquals(429, error.httpStatusCode)
        assertEquals(45_000L, error.retryAfterMs)
        assertEquals("Translation request failed (HTTP 429)", error.message)
        withTimeout(5_000) { released.await() }
    }

    @Test
    fun `cancelling before headers cancels the HTTP call`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val call = newCall()
        val response = async(start = CoroutineStart.UNDISPATCHED) { call.awaitTranslationBody() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        withTimeout(5_000) { response.cancelAndJoin() }

        assertTrue(call.isCanceled())
        assertTrue(response.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling during a slow body read cancels the HTTP call`() = runBlocking {
        val bodyStarted = CompletableDeferred<Unit>()
        val callFailedSignal = CompletableDeferred<Unit>()
        client = client.newBuilder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                bodyStarted.complete(Unit)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                callFailedSignal.complete(Unit)
            }
        }).build()
        server.enqueue(
            MockResponse.Builder()
                .body("a".repeat(10_000))
                .throttleBody(1, 1, TimeUnit.SECONDS)
                .build(),
        )
        val call = newCall()
        val response = async(start = CoroutineStart.UNDISPATCHED) { call.awaitTranslationBody() }
        withTimeout(5_000) { bodyStarted.await() }
        assertFalse(response.isCompleted)

        withTimeout(5_000) { response.cancelAndJoin() }

        assertTrue(call.isCanceled())
        withTimeout(5_000) { callFailedSignal.await() }
        assertEquals(1, server.requestCount)
    }

    private fun newCall(): Call = client.newCall(Request.Builder().url(server.url("/translate")).build())
}

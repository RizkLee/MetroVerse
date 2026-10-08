package com.metrolist.music.api

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class OpenRouterServiceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `strict subtitle translation never accepts partial or extra provider lines`() = runBlocking {
        listOf(listOf("one"), listOf("one", "two", "three")).forEach { lines ->
            server.enqueue(response(lines))
            val result = translate(strictLineCount = true)
            assertTrue(result.exceptionOrNull() is TranslationRequestException)
        }
        server.enqueue(response(listOf("one", "two")))
        assertEquals(listOf("one", "two"), translate(strictLineCount = true).getOrThrow())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `default lyrics translation retains its padding and truncation`() = runBlocking {
        server.enqueue(response(listOf("one")))
        assertEquals(listOf("one", ""), translate().getOrThrow())
        server.enqueue(response(listOf("one", "two", "three")))
        assertEquals(listOf("one", "two"), translate().getOrThrow())
    }

    @Test
    fun `authentication and rate limit errors stop without retrying`() = runBlocking {
        listOf(401, 403, 429).forEach { code ->
            server.enqueue(
                MockResponse.Builder().code(code).addHeader("Retry-After", "30").body("ignored").build(),
            )
            val error = translate(maxRetries = 3).exceptionOrNull() as TranslationRequestException
            assertEquals(code, error.httpStatusCode)
            assertEquals(30_000L, error.retryAfterMs)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `server errors retain their final status after retries are exhausted`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(502).build())
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "15").build())

        val error = translate(maxRetries = 2).exceptionOrNull() as TranslationRequestException

        assertEquals(503, error.httpStatusCode)
        assertEquals(15_000L, error.retryAfterMs)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cancelled provider request exits without retrying`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val translation = async { translate(maxRetries = 3) }
        try {
            val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull(request)

            withTimeout(5_000) { translation.cancelAndJoin() }

            assertTrue(translation.isCancelled)
            assertEquals(1, server.requestCount)
        } finally {
            translation.cancel()
            server.close()
        }
    }

    private suspend fun translate(
        strictLineCount: Boolean = false,
        maxRetries: Int = 1,
    ): Result<List<String>> = withTimeout(10_000) {
        OpenRouterService.translate(
            text = "first\nsecond",
            targetLanguage = "Spanish",
            apiKey = "",
            baseUrl = server.url("/translate").toString(),
            model = "test-model",
            mode = "Translated",
            maxRetries = maxRetries,
            strictLineCount = strictLineCount,
        )
    }

    private fun response(lines: List<String>): MockResponse {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", JSONArray(lines).toString())),
            ),
        )
        return MockResponse.Builder().body(body.toString()).build()
    }
}

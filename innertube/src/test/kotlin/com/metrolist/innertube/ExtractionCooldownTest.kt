/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube

import com.metrolist.innertubex.extraction.StreamResolveException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ExtractionCooldownTest {
    @Test
    fun `429 triggers exponential backoff and bypasses non-youtube hosts`() = runBlocking {
        var nowMs = 1_000L
        val cooldown = ExtractionCooldown(elapsedMs = { nowMs }, wallTimeMs = { nowMs })
        val engine = MockEngine { request ->
            if (request.url.host == "youtubei.googleapis.com") {
                respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "10"))
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        HttpClient(engine) { install(ExtractionHttpCooldown) }.use { client ->
            val err = runCatching {
                cooldown.run {
                    client.get("https://youtubei.googleapis.com/youtubei/v1/player") {
                        header("X-YouTube-Client-Name", "67")
                    }
                }
            }.exceptionOrNull()
            assertTrue(err is StreamResolveException)
            assertEquals(StreamResolveException.Reason.NETWORK, (err as StreamResolveException).reason)

            // Blocked before Retry-After elapses; MockEngine shouldn't even be invoked.
            nowMs = 5_000L
            val blocked = runCatching {
                cooldown.run {
                    client.get("https://youtubei.googleapis.com/youtubei/v1/player")
                }
            }.exceptionOrNull()
            assertTrue(blocked is StreamResolveException)
            assertEquals(1, engine.requestHistory.size)

            // Non-YouTube hosts (e.g. RSS podcasts) bypass ExtractionHttpCooldown plugin completely.
            val rssResp = client.get("https://feeds.example.org/podcast.xml")
            assertEquals(HttpStatusCode.OK, rssResp.status)

            // Once Retry-After elapses, requests are allowed again.
            nowMs = 12_000L
            cooldown.beforeRequest()
        }
    }

    @Test
    fun `two distinct player clients failing within 30s trips cooldown`() = runBlocking {
        var nowMs = 1_000L
        val cooldown = ExtractionCooldown(elapsedMs = { nowMs }, wallTimeMs = { nowMs })
        cooldown.transportFailure("67")
        nowMs += 5_000L
        val err = runCatching { cooldown.transportFailure("1") }.exceptionOrNull()
        assertTrue(err is ExtractionDeferred)
        val blocked = runCatching { cooldown.beforeRequest() }.exceptionOrNull()
        assertTrue(blocked is ExtractionDeferred)
        nowMs += 6_000L
        cooldown.beforeRequest()
    }

    @Test
    fun `registerPlayback rejects untrusted tracking urls`() = runBlocking {
        val engine = MockEngine { respond("") }
        HttpClient(engine).use { client ->
            val api = InnerTube().apply { cookie = "__Secure-3PAPISID=abc" }
            for (badUrl in listOf(
                "https://example.com/api/stats/playback",
                "http://s.youtube.com/api/stats/playback",
                "https://s.youtube.com:444/api/stats/playback",
                "https://user:pass@s.youtube.com/api/stats/playback",
                "https://s.youtube.com/api/stats/watchtime",
            )) {
                val failure = runCatching { api.requireTrustedPlaybackTrackingUrl(badUrl) }.exceptionOrNull()
                assertTrue(badUrl, failure is IllegalArgumentException)
            }
            api.requireTrustedPlaybackTrackingUrl("https://s.youtube.com/api/stats/playback?docid=123")
        }
    }

    @Test
    fun `unsupported io exception does not break non-youtube flows`() {
        val io = IOException("transient")
        assertTrue(io.message == "transient")
    }
}

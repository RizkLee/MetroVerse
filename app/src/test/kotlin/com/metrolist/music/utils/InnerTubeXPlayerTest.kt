/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.ExtractedStream
import kotlinx.coroutines.CancellationException
import com.metrolist.innertubex.extraction.YtConfigParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeXPlayerTest {
    @After
    fun tearDown() {
        InnerTubeXPlayer.clearStreamClientFailures()
    }

    @Test
    fun `failed stream clients accumulate per video and expire`() {
        InnerTubeXPlayer.markStreamClientFailed("song", "VISIONOS", nowMs = 1_000L)
        InnerTubeXPlayer.markStreamClientFailed("song", "WEB_REMIX", nowMs = 2_000L)

        assertEquals(
            setOf("VISIONOS", "WEB_REMIX"),
            InnerTubeXPlayer.failedStreamClients("song", nowMs = 2_000L),
        )
        assertTrue(InnerTubeXPlayer.failedStreamClients("other", nowMs = 2_000L).isEmpty())
        assertTrue(InnerTubeXPlayer.failedStreamClients("song", nowMs = 302_000L).isEmpty())
    }

    @Test
    fun `cancelled config fetch never invokes embedded fallback`() = runBlocking {
        var embeddedCalls = 0
        val parser = object : YtConfigParser {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
                throw CancellationException("cancelled")
            override suspend fun fetchEmbeddedConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig {
                embeddedCalls++
                return PlayerConfig("embedded", 123, null, null)
            }
        }
        var cancelled = false
        try {
            InnerTubeXPlayer.run { parser.withEmbeddedConfigFallback() }.fetchConfig("song", true)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0, embeddedCalls)
    }

    @Test
    fun `stream mapping retains codecs headers range policy and nullable length`() {
        val stream = ExtractedStream(
            videoId = "song", audioUrl = "https://example.com/audio", headers = mapOf("User-Agent" to "client"),
            loudnessDb = -8.0, expiresAt = null, contentLengthBytes = null, itag = 251,
            mimeType = "audio/webm", codecs = "opus", bitrate = 128_000, sampleRate = 48_000,
            clientName = "WEB_REMIX", profileId = "WEB_REMIX", requireBoundedRange = true,
            rangeChunkSizeBytes = 1_024, useRangeChunks = true,
        )
        val mapped = InnerTubeXPlayer.run { stream.toPlaybackData() }
        assertEquals("audio/webm; codecs=\"opus\"", mapped.format.mimeType)
        assertEquals(null, mapped.format.contentLength)
        assertEquals(stream.headers, mapped.streamHeaders)
        assertEquals(1_024L, mapped.rangeChunkSizeBytes)
        assertTrue(mapped.requireBoundedRange)
        assertTrue(mapped.useRangeChunks)
        assertEquals(-8.0, mapped.audioConfig?.loudnessDb)
        assertEquals(300, mapped.streamExpiresInSeconds)
    }

    @Test
    fun `failed watch page falls back to anonymous embedded config`() =
        runBlocking {
            val expected = PlayerConfig("embedded", 123, null, null)
            var embeddedUsesLogin = true
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
                        error("HTTP 302")

                    override suspend fun fetchEmbeddedConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig {
                        embeddedUsesLogin = useLoginCookies
                        return expected
                    }
                }

            assertEquals(expected, InnerTubeXPlayer.run { parser.withEmbeddedConfigFallback() }.fetchConfig("song", true))
            assertEquals(false, embeddedUsesLogin)
        }
}

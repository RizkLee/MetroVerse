/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.music.utils.potoken.WebPoTokenHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeXPlayerTest {
    @After
    fun tearDown() {
        InnerTubeXPlayer.clearStreamClientFailures()
        WebPoTokenHealth.reset()
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
        assertEquals("WEB_REMIX", mapped.profileId)
        assertEquals(-8.0, mapped.audioConfig?.loudnessDb)
        assertEquals(300, mapped.streamExpiresInSeconds)
    }

    @Test
    fun `resolveWithClientFallback falls back to hard exclusions at low quality when no playable stream`() =
        runBlocking {
            val attempts = mutableListOf<Pair<Set<String>, Boolean>>()
            val resolved =
                InnerTubeXPlayer.resolveWithClientFallback(
                    excludedClients = setOf("WEB_REMIX", "TVHTML5"),
                    hardExclusions = setOf("TVHTML5"),
                ) { excluded, useLowQuality ->
                    attempts += excluded to useLowQuality
                    if (!useLowQuality) {
                        throw StreamResolveException(
                            reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                            message = "none",
                        )
                    }
                    "fallback-stream"
                }

            assertEquals("fallback-stream", resolved)
            assertEquals(
                listOf(
                    setOf("WEB_REMIX", "TVHTML5") to false,
                    setOf("TVHTML5") to true,
                ),
                attempts,
            )
        }

    @Test
    fun `resolveWithClientFallback rethrows non-playable stream errors immediately`() =
        runBlocking {
            var calls = 0
            var caught: StreamResolveException? = null
            try {
                InnerTubeXPlayer.resolveWithClientFallback(
                    excludedClients = setOf("WEB_REMIX"),
                    hardExclusions = emptySet(),
                ) { _, _ ->
                    calls++
                    throw StreamResolveException(
                        reason = StreamResolveException.Reason.AGE_RESTRICTED,
                        message = "age",
                    )
                }
            } catch (e: StreamResolveException) {
                caught = e
            }

            assertEquals(1, calls)
            assertEquals(StreamResolveException.Reason.AGE_RESTRICTED, caught?.reason)
        }

    @Test
    fun `usesOriginalAudioTrack distinguishes original and dubbed xtags`() {
        assertTrue(usesOriginalAudioTrack("https://rr1.googlevideo.com/videoplayback?itag=251"))
        assertTrue(usesOriginalAudioTrack("https://rr1.googlevideo.com/videoplayback?xtags=acont%3Doriginal"))
        assertFalse(usesOriginalAudioTrack("https://rr1.googlevideo.com/videoplayback?xtags=acont%3Ddubbed"))
        assertFalse(usesOriginalAudioTrack("https://rr1.googlevideo.com/videoplayback", formatTags = "lang=es:acont=dubbed"))
        assertTrue(usesOriginalAudioTrack("https://rr1.googlevideo.com/videoplayback", formatTags = "lang=en&acont=original"))
    }

    @Test
    fun `web po token rejection expires after ttl and records 403 on __po profile`() {
        assertFalse(WebPoTokenHealth.tokensRejected(nowMs = 1_000L))
        InnerTubeXPlayer.markStreamClientFailed(
            videoId = "song1",
            clientName = "WEB_REMIX",
            profileId = "WEB_REMIX__po",
            responseCode = 403,
            nowMs = 1_000L,
        )
        assertTrue(WebPoTokenHealth.tokensRejected(nowMs = 1_000L))
        assertTrue(WebPoTokenHealth.tokensRejected(nowMs = 30 * 60_000L))
        assertFalse(WebPoTokenHealth.tokensRejected(nowMs = 30 * 60_000L + 1_000L))
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

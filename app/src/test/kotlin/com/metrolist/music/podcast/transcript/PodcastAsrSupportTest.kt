/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastAsrSupportTest {

    @Test
    fun `resample mono 16k passes through`() {
        val pcm = shortArrayOf(0, 16384, -16384, 32767)
        val out = PodcastAsrSupport.resampleToMono16k(pcm, channelCount = 1, sourceSampleRate = 16_000)

        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-3f)
        assertEquals(-0.5f, out[2], 1e-3f)
    }

    @Test
    fun `resample stereo downmixes channels`() {
        // L=32767, R=-32767 → mono 0.0
        val pcm = shortArrayOf(32767, -32767)
        val out = PodcastAsrSupport.resampleToMono16k(pcm, channelCount = 2, sourceSampleRate = 16_000)

        assertEquals(1, out.size)
        assertEquals(0f, out[0], 1e-4f)
    }

    @Test
    fun `resample handles empty input`() {
        assertTrue(PodcastAsrSupport.resampleToMono16k(ShortArray(0), channelCount = 2, sourceSampleRate = 44_100).isEmpty())
    }

    @Test
    fun `splitLongCue keeps short cue untouched`() {
        val cue = SubtitleCue(0, 5_000, "Hello world")
        val split = PodcastAsrSupport.splitLongCue(cue)

        assertEquals(1, split.size)
        assertEquals(cue, split[0])
    }

    @Test
    fun `splitLongCue splits long cue proportionally in time`() {
        val text = (1..40).joinToString(" ") { "word$it" }
        val cue = SubtitleCue(0, 40_000, text)
        val split = PodcastAsrSupport.splitLongCue(cue)

        assertTrue(split.size >= 3)
        assertEquals(0L, split.first().startMs)
        assertEquals(40_000L, split.last().endMs)
        // Time coverage must be contiguous.
        for (i in 1 until split.size) {
            assertEquals(split[i - 1].endMs, split[i].startMs)
        }
        // All words preserved.
        assertEquals(text, split.joinToString(" ") { it.text })
    }

    @Test
    fun `cueIndexAt finds covering cue and falls back to last started cue`() {
        val cues = listOf(
            SubtitleCue(0, 1_000, "a"),
            SubtitleCue(2_000, 3_000, "b"),
            SubtitleCue(4_000, 5_000, "c"),
        )

        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, 500))
        assertEquals(1, PodcastAsrSupport.cueIndexAt(cues, 2_500))
        // Gap between cue a and b still shows a (last started cue).
        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, 1_500))
        assertEquals(2, PodcastAsrSupport.cueIndexAt(cues, 10_000))
        assertEquals(-1, PodcastAsrSupport.cueIndexAt(emptyList(), 1_000))
    }

    @Test
    fun `normalizeLanguageTag strips sensevoice markers`() {
        assertEquals("zh", PodcastAsrSupport.normalizeLanguageTag("<|zh|>"))
        assertEquals("en", PodcastAsrSupport.normalizeLanguageTag("EN"))
        assertEquals("yue", PodcastAsrSupport.normalizeLanguageTag("<|yue|>"))
        assertNull(PodcastAsrSupport.normalizeLanguageTag(null))
        assertNull(PodcastAsrSupport.normalizeLanguageTag("   "))
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTimingOffsetTest {
    @Test
    fun `positive offsets highlight and seek to the same earlier audio position`() {
        val cues = listOf(SubtitleCue(5_000L, 8_000L, "First"), SubtitleCue(8_000L, 12_000L, "Second"))
        val target = SubtitleTimingOffset.playbackPosition(cues[1].startMs, 750)

        assertEquals(7_250L, target)
        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, SubtitleTimingOffset.subtitlePosition(target - 1L, 750)))
        assertEquals(1, PodcastAsrSupport.cueIndexAt(cues, SubtitleTimingOffset.subtitlePosition(target, 750)))
    }

    @Test
    fun `negative offsets delay both highlighting and seeking`() {
        val cues = listOf(SubtitleCue(5_000L, 8_000L, "First"), SubtitleCue(8_000L, 12_000L, "Second"))
        val target = SubtitleTimingOffset.playbackPosition(cues[1].startMs, -750)

        assertEquals(8_750L, target)
        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, SubtitleTimingOffset.subtitlePosition(target - 1L, -750)))
        assertEquals(1, PodcastAsrSupport.cueIndexAt(cues, SubtitleTimingOffset.subtitlePosition(target, -750)))
    }

    @Test
    fun `a delayed zero-start cue stays unhighlighted until its adjusted start`() {
        val cues = listOf(SubtitleCue(0L, 5_000L, "First"))

        assertEquals(-1, PodcastAsrSupport.cueIndexAt(cues, SubtitleTimingOffset.subtitlePosition(499L, -500)))
        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, SubtitleTimingOffset.subtitlePosition(500L, -500)))
    }

    @Test
    fun `adjusted seeks stay within the playable episode`() {
        assertEquals(0L, SubtitleTimingOffset.playbackPosition(200L, 500))
        assertEquals(10_000L, SubtitleTimingOffset.playbackPosition(9_800L, -500, durationMs = 10_000L))
        assertEquals(10_300L, SubtitleTimingOffset.playbackPosition(9_800L, -500, durationMs = -1L))
    }

    @Test
    fun `extreme stored offsets cannot overflow the subtitle or playback clock`() {
        assertEquals(10_000, SubtitleTimingOffset.clamp(Int.MAX_VALUE))
        assertEquals(-10_000, SubtitleTimingOffset.clamp(Int.MIN_VALUE))
        assertEquals(Long.MAX_VALUE, SubtitleTimingOffset.subtitlePosition(Long.MAX_VALUE - 1L, 50))
        assertEquals(Long.MAX_VALUE, SubtitleTimingOffset.playbackPosition(Long.MAX_VALUE - 1L, -50))
    }
}

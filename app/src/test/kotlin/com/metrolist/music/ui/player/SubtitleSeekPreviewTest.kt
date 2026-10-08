/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import com.metrolist.music.podcast.transcript.PodcastAsrSupport
import com.metrolist.music.podcast.transcript.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSeekPreviewTest {
    @Test
    fun oldPlaybackPositionCannotPullTheListBackAfterTap() {
        val preview = SubtitleSeekPreview()
        preview.request(90, 1_000)

        assertEquals(90, preview.index(3, 120, 1_250))
        assertEquals(90, preview.index(90, 120, 2_000))
        assertEquals(91, preview.index(91, 120, 2_700))
    }

    @Test
    fun latestTapReplacesAnUnacknowledgedSeek() {
        val preview = SubtitleSeekPreview()
        preview.request(90, 1_000)
        preview.request(10, 1_500)

        assertEquals(10, preview.index(90, 120, 1_600))
        assertEquals(10, preview.index(10, 120, 1_700))
    }

    @Test
    fun rejectedSeekEventuallyReturnsToRealPlayback() {
        val preview = SubtitleSeekPreview()
        preview.request(90, 1_000)

        assertEquals(90, preview.index(3, 120, 4_999))
        assertEquals(4, preview.index(4, 120, 5_000))
        assertEquals(5, preview.index(5, 120, 6_000))
    }

    @Test
    fun removedCueDoesNotLeaveAStaleTarget() {
        val preview = SubtitleSeekPreview()
        preview.request(90, 1_000)

        assertEquals(3, preview.index(3, 12, 1_250))
        assertEquals(4, preview.index(4, 120, 1_500))
    }

    @Test
    fun pausedSeekAtSharedCueBoundaryKeepsTheSelectedLineAfterTimeout() {
        val cues = listOf(
            SubtitleCue(0, 1_000, "First"),
            SubtitleCue(1_000, 2_000, "Second"),
            SubtitleCue(2_000, 3_000, "Third"),
        )
        val preview = SubtitleSeekPreview()
        preview.request(2, 1_000)
        val acknowledged = PodcastAsrSupport.cueIndexAt(cues, 2_000)

        assertEquals(2, preview.index(acknowledged, cues.size, 1_250))
        assertEquals(2, preview.index(acknowledged, cues.size, 6_000))
    }
}

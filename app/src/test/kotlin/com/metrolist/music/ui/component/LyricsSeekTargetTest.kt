/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSeekTargetTest {
    @Test
    fun `old playback indices cannot reclaim a clicked target before confirmation`() {
        val target = LyricsSeekTarget(index = 12, positionMs = 60_000L, requestedAtMs = 1_000L)

        assertTrue(target.isPending(playbackIndex = 3, nowMs = 1_100L))
        assertTrue(target.isPending(playbackIndex = 18, nowMs = 1_800L))
        assertFalse(target.isPending(playbackIndex = 12, nowMs = 1_900L))
    }

    @Test
    fun `a failed seek eventually releases the scroll target`() {
        val target = LyricsSeekTarget(index = 0, positionMs = 0L, requestedAtMs = 1_000L)

        assertTrue(target.isPending(playbackIndex = 15, nowMs = 2_999L))
        assertFalse(target.isPending(playbackIndex = 15, nowMs = 3_000L))
    }

    @Test
    fun `a delayed confirmation of the previous click cannot replace the latest click`() {
        val first = LyricsSeekTarget(index = 20, positionMs = 90_000L, requestedAtMs = 1_000L)
        val latest = LyricsSeekTarget(index = 4, positionMs = 12_000L, requestedAtMs = 1_400L)

        assertTrue(latest.isPending(playbackIndex = first.index, nowMs = 1_600L))
        assertTrue(latest.isPending(playbackIndex = first.index, nowMs = 3_100L))
        assertFalse(latest.isPending(playbackIndex = latest.index, nowMs = 3_150L))
    }
}

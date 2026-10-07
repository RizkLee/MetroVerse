/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRequestOwnershipTest {
    @Test
    fun `switching back to the same episode does not revive its old request`() {
        assertFalse(isCurrentSubtitleRequest(1, 3, "episode-a", "episode-a"))
        assertTrue(isCurrentSubtitleRequest(3, 3, "episode-a", "episode-a"))
    }

    @Test
    fun `clear or configuration change rejects an earlier translation response`() {
        assertFalse(isCurrentSubtitleRequest(4, 5, "episode-a", "episode-a"))
        assertFalse(isCurrentSubtitleRequest(4, 4, "episode-a", null))
        assertFalse(isCurrentSubtitleRequest(4, 4, "episode-a", "episode-b"))
    }
}

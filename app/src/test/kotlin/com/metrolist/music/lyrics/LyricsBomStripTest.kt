/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsBomStripTest {
    @Test
    fun `strips leading utf8 bom when parsing standard and rich synced lyrics`() {
        val syncedWithBom = "\uFEFF[00:05.00]First synced line\n[00:10.00]Second synced line"
        val parsedSynced = LyricsUtils.parseLyrics(syncedWithBom)
        assertEquals(2, parsedSynced.size)
        assertEquals(5_000L, parsedSynced[0].time)
        assertEquals("First synced line", parsedSynced[0].text)

        val richWithBom = "\uFEFF[00:05.00]<00:05.10>First <00:05.50>word\n[00:10.00]<00:10.10>Next"
        val parsedRich = LyricsUtils.parseLyrics(richWithBom)
        assertEquals(2, parsedRich.size)
        assertEquals(5_000L, parsedRich[0].time)
        assertTrue(parsedRich[0].words?.isNotEmpty() == true)
    }
}

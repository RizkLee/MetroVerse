/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPreviewSupportTest {
    @Test
    fun `playing song with synced lyrics uses live synced lines and provider`() {
        val lrc =
            """
            [00:01.00]<00:01.00>First <00:01.50>line
            [00:03.00]<00:03.00>Second <00:03.50>line
            [00:05.00]<00:05.00>Third <00:05.50>line
            """.trimIndent()

        val result =
            resolveLyricsPreviewSource(
                isEffectivelyPlaying = true,
                isPodcastEpisode = false,
                rawLyrics = lrc,
                provider = "BetterLyrics",
            )

        assertTrue(result.isLivePlayback)
        assertTrue(result.isSynced)
        assertEquals("BetterLyrics", result.provider)
        assertEquals(3, result.lines.size)
        assertEquals("First line", result.lines[0].text)
    }

    @Test
    fun `playing song with plain lyrics displays real plain lines in unsynced mode`() {
        val plain = "Line one of song\nLine two of song\nLine three of song"

        val result =
            resolveLyricsPreviewSource(
                isEffectivelyPlaying = true,
                isPodcastEpisode = false,
                rawLyrics = plain,
                provider = "LRCLIB",
            )

        assertFalse(result.isLivePlayback)
        assertFalse(result.isSynced)
        assertEquals("LRCLIB", result.provider)
        assertEquals(listOf("Line one of song", "Line two of song", "Line three of song"), result.lines.map { it.text })
    }

    @Test
    fun `paused playback, podcasts, or missing lyrics fall back to rich multi-line sample`() {
        val lrc = "[00:01.00]Real song line"

        val paused =
            resolveLyricsPreviewSource(
                isEffectivelyPlaying = false,
                isPodcastEpisode = false,
                rawLyrics = lrc,
                provider = "LRCLIB",
            )
        val podcast =
            resolveLyricsPreviewSource(
                isEffectivelyPlaying = true,
                isPodcastEpisode = true,
                rawLyrics = lrc,
                provider = "Podcast",
            )
        val notFound =
            resolveLyricsPreviewSource(
                isEffectivelyPlaying = true,
                isPodcastEpisode = false,
                rawLyrics = LYRICS_NOT_FOUND,
                provider = "None",
            )

        for (fallback in listOf(paused, podcast, notFound)) {
            assertFalse(fallback.isLivePlayback)
            assertTrue(fallback.isSynced)
            assertEquals("MetroVerse", fallback.provider)
            assertTrue(fallback.lines.size >= 6)
            assertTrue(fallback.lines.all { !it.words.isNullOrEmpty() })
        }
    }
}

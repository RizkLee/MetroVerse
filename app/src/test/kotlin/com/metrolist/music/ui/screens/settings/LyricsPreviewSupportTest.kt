/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import com.metrolist.music.constants.LyricsAnimationStyle
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
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

    @Test
    fun `default sample lyrics lines stay concise with descenders and monotonic word timings`() {
        val sample = buildPreviewLyricsSample()
        assertTrue(sample.size >= 6)
        assertTrue(sample.all { entry -> entry.text.length <= 19 })
        assertTrue(sample.all { entry -> entry.text.split(Regex("\\s+")).size <= 4 })
        assertTrue(sample.any { entry -> entry.text.any { it in setOf('g', 'y', 'p', 'j') } })

        var previousLineTime = -1L
        var previousWordEndSec = -0.001
        for (entry in sample) {
            assertTrue("Line timestamps must increase", entry.time > previousLineTime)
            previousLineTime = entry.time
            val words = entry.words.orEmpty()
            assertTrue("Each sample line must have word timings", words.isNotEmpty())
            assertEquals((entry.time / 1000.0), words.first().startTime, 0.01)
            for (word in words) {
                assertTrue("Word start must not regress", word.startTime >= previousWordEndSec)
                assertTrue("Word end must follow start", word.endTime > word.startTime)
                previousWordEndSec = word.endTime
            }
        }
    }

    @Test
    fun `live preview scroll anchor progresses monotonically past ultra-short lines without oscillating`() {
        val lines = listOf(
            LyricsEntry(0L, "Long opening line", listOf(WordTimestamp("Long", 0.0, 2.0))),
            LyricsEntry(2_000L, "Quick!", listOf(WordTimestamp("Quick!", 2.0, 2.5))),
            LyricsEntry(2_550L, "Following chorus line", listOf(WordTimestamp("Following", 2.55, 4.5))),
        )
        // Frame at 1_600ms (with 450ms look-ahead from active line 0 -> 2_050ms enters ultra-short line 1).
        val enteredShortLine =
            resolvePreviewScrollAnchorIndex(
                lines = lines,
                currentHighlightIndex = 0,
                previousScrollAnchorIndex = 0,
                effectivePositionMs = 1_600L,
                previousPositionMs = 1_550L,
                hasWordTimings = true,
            )
        assertEquals(1, enteredShortLine)

        // Frame at 1_900ms while still on line 0: 1_900 + 450 = 2_350ms (< 2_550ms), stays monotonic on line 1.
        val stayedOnShortLine =
            resolvePreviewScrollAnchorIndex(
                lines = lines,
                currentHighlightIndex = 0,
                previousScrollAnchorIndex = enteredShortLine,
                effectivePositionMs = 1_900L,
                previousPositionMs = 1_600L,
                hasWordTimings = true,
            )
        assertEquals(1, stayedOnShortLine)

        // Once activeHighlight advances onto ultra-short line 1 (2_050ms + 275ms look-ahead = 2_325ms),
        // it never regresses back to 0 and stays on 1 until reaching line 2's look-ahead window.
        val whileSingingShortLine =
            resolvePreviewScrollAnchorIndex(
                lines = lines,
                currentHighlightIndex = 1,
                previousScrollAnchorIndex = stayedOnShortLine,
                effectivePositionMs = 2_050L,
                previousPositionMs = 1_900L,
                hasWordTimings = true,
            )
        assertEquals(1, whileSingingShortLine)

        val advancedToFollowingLine =
            resolvePreviewScrollAnchorIndex(
                lines = lines,
                currentHighlightIndex = 1,
                previousScrollAnchorIndex = whileSingingShortLine,
                effectivePositionMs = 2_300L,
                previousPositionMs = 2_050L,
                hasWordTimings = true,
            )
        assertEquals(2, advancedToFollowingLine)

        // Seeking backward to 400ms rebases cleanly back to line 0.
        val afterBackwardSeek =
            resolvePreviewScrollAnchorIndex(
                lines = lines,
                currentHighlightIndex = 0,
                previousScrollAnchorIndex = advancedToFollowingLine,
                effectivePositionMs = 400L,
                previousPositionMs = 2_300L,
                hasWordTimings = true,
            )
        assertEquals(0, afterBackwardSeek)
    }

    @Test
    fun `preview scroll anchor respects style-specific look-ahead between Apple Music and MetroVerse`() {
        val sample = buildPreviewLyricsSample()
        // Line 0 word ends at 2_050ms; line 1 starts at 2_200ms.
        // At 1_800ms (400ms before line 1):
        // - Apple Music uses 450ms look-ahead (1_800 + 450 = 2_250ms >= 2_200ms) -> scrolls early to line 1.
        // - MetroVerse uses 0ms word look-ahead (1_800 + 0 = 1_800ms < 2_200ms) -> stays on line 0 until line 1 starts.
        assertEquals(
            1,
            resolvePreviewScrollAnchorIndex(
                lines = sample,
                currentHighlightIndex = 0,
                previousScrollAnchorIndex = 0,
                effectivePositionMs = 1_800L,
                previousPositionMs = 1_750L,
                hasWordTimings = true,
                animationStyle = LyricsAnimationStyle.APPLE,
            ),
        )
        assertEquals(
            0,
            resolvePreviewScrollAnchorIndex(
                lines = sample,
                currentHighlightIndex = 0,
                previousScrollAnchorIndex = 0,
                effectivePositionMs = 1_800L,
                previousPositionMs = 1_750L,
                hasWordTimings = true,
                animationStyle = LyricsAnimationStyle.METROVERSE,
            ),
        )
        assertEquals(
            1,
            resolvePreviewScrollAnchorIndex(
                lines = sample,
                currentHighlightIndex = 1,
                previousScrollAnchorIndex = 0,
                effectivePositionMs = 2_200L,
                previousPositionMs = 1_800L,
                hasWordTimings = true,
                animationStyle = LyricsAnimationStyle.METROVERSE,
            ),
        )
    }
}

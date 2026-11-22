/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component.lyrics

import androidx.compose.ui.text.font.FontWeight
import com.metrolist.music.constants.LYRICS_TEXT_SIZE_RANGE
import com.metrolist.music.constants.LyricsAnimationStyle
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.lyrics.shouldFetchSongwriters
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.LyricsListItem
import com.metrolist.music.ui.component.lyrics.words.lyricsWordAnimationProgress
import com.metrolist.music.ui.component.lyrics.words.lyricsWordAnimationRangeMs
import com.metrolist.music.ui.component.resolveLyricsListAnchorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsVisualHelpersTest {
    @Test
    fun `progressive blur scales monotonically with distance and stays zero on attribution footer`() {
        assertEquals(
            0f,
            computeProgressiveLyricsBlurDp(
                distance = 0,
                enabled = true,
                isSynced = true,
                isAutoScrollEnabled = true,
                isManualScrolling = false,
                isSelectionMode = false,
            ),
            0f,
        )

        val d1 = computeProgressiveLyricsBlurDp(1, true, true, true, false, false)
        val d2 = computeProgressiveLyricsBlurDp(2, true, true, true, false, false)
        val d5 = computeProgressiveLyricsBlurDp(5, true, true, true, false, false)
        val d12 = computeProgressiveLyricsBlurDp(12, true, true, true, false, false)
        assertTrue(d1 > 0f)
        assertTrue(d2 > d1)
        assertTrue(d5 >= d2)
        assertEquals(7.5f, d12, 0.001f)

        // End credits (provider & songwriters) must NEVER blur even when progressive blur is enabled.
        for (distance in listOf(0, 1, 3, 10)) {
            assertEquals(
                0f,
                computeProgressiveLyricsBlurDp(
                    distance = distance,
                    enabled = true,
                    isSynced = true,
                    isAutoScrollEnabled = true,
                    isManualScrolling = false,
                    isSelectionMode = false,
                    isAttributionItem = true,
                ),
                0f,
            )
        }
    }

    @Test
    fun `progressive blur clears immediately when disabled, unsynced, scrolling, or selecting`() {
        assertEquals(0f, computeProgressiveLyricsBlurDp(3, enabled = false, isSynced = true, isAutoScrollEnabled = true, isManualScrolling = false, isSelectionMode = false), 0f)
        assertEquals(0f, computeProgressiveLyricsBlurDp(3, enabled = true, isSynced = false, isAutoScrollEnabled = true, isManualScrolling = false, isSelectionMode = false), 0f)
        assertEquals(0f, computeProgressiveLyricsBlurDp(3, enabled = true, isSynced = true, isAutoScrollEnabled = false, isManualScrolling = false, isSelectionMode = false), 0f)
        assertEquals(0f, computeProgressiveLyricsBlurDp(3, enabled = true, isSynced = true, isAutoScrollEnabled = true, isManualScrolling = true, isSelectionMode = false), 0f)
        assertEquals(0f, computeProgressiveLyricsBlurDp(3, enabled = true, isSynced = true, isAutoScrollEnabled = true, isManualScrolling = false, isSelectionMode = true), 0f)
    }

    @Test
    fun `appendLyricsAttributionItem filters Unknown and Manual providers and deduplicates writers`() {
        val base = listOf(LyricsListItem.Line(0, LyricsEntry(0L, "Hello")))
        assertEquals(base, appendLyricsAttributionItem(base, "Unknown", emptyList()))
        assertEquals(base, appendLyricsAttributionItem(base, "Manual", listOf("  ")))

        val withBoth = appendLyricsAttributionItem(base, "BetterLyrics", listOf("Alice", "Alice ", "Bob"))
        assertEquals(2, withBoth.size)
        val footer = withBoth.last() as LyricsListItem.Provider
        assertEquals("BetterLyrics", footer.provider)
        assertEquals(listOf("Alice", "Bob"), footer.songwriters)

        val writersOnly = appendLyricsAttributionItem(base, "Unknown", listOf("Clara"))
        val footerOnlyWriters = writersOnly.last() as LyricsListItem.Provider
        assertEquals(null, footerOnlyWriters.provider)
        assertEquals(listOf("Clara"), footerOnlyWriters.songwriters)
    }

    @Test
    fun `LyricsAnimationStyle migrates legacy styles cleanly`() {
        for (legacy in listOf("FADE", "GLOW", "SLIDE", "KARAOKE", "METROVERSE", null, "unknown")) {
            assertEquals(LyricsAnimationStyle.METROVERSE, LyricsAnimationStyle.fromStoredValue(legacy))
        }
        assertEquals(LyricsAnimationStyle.APPLE, LyricsAnimationStyle.fromStoredValue("apple"))
        assertEquals(LyricsAnimationStyle.APPLE, LyricsAnimationStyle.fromStoredValue("APPLE"))
        assertEquals(LyricsAnimationStyle.NONE, LyricsAnimationStyle.fromStoredValue("none"))
    }

    @Test
    fun `zoomedLyricsTextSize clamps within bounds and ignores non-finite or non-positive zoom`() {
        assertEquals(LYRICS_TEXT_SIZE_RANGE.start, zoomedLyricsTextSize(16f, 0.2f), 0.001f)
        assertEquals(LYRICS_TEXT_SIZE_RANGE.endInclusive, zoomedLyricsTextSize(36f, 3.0f), 0.001f)
        assertEquals(36f, zoomedLyricsTextSize(36f, Float.NaN), 0.001f)
        assertEquals(36f, zoomedLyricsTextSize(36f, -1f), 0.001f)
        assertEquals(36f, zoomedLyricsTextSize(36f, 0f), 0.001f)
    }

    @Test
    fun `computeAppleDistanceScale applies depth step-down only during active synced follow`() {
        assertEquals(1.0f, computeAppleDistanceScale(distance = 4, isActive = true, isSynced = true, isAutoScrollEnabled = true, isSelectionMode = false), 0f)
        assertEquals(1.0f, computeAppleDistanceScale(distance = 4, isActive = false, isSynced = false, isAutoScrollEnabled = true, isSelectionMode = false), 0f)
        assertEquals(1.0f, computeAppleDistanceScale(distance = 4, isActive = false, isSynced = true, isAutoScrollEnabled = false, isSelectionMode = false), 0f)
        assertEquals(1.0f, computeAppleDistanceScale(distance = 4, isActive = false, isSynced = true, isAutoScrollEnabled = true, isSelectionMode = true), 0f)

        assertEquals(0.97f, computeAppleDistanceScale(1, isActive = false, isSynced = true, isAutoScrollEnabled = true, isSelectionMode = false), 0f)
        assertEquals(0.93f, computeAppleDistanceScale(3, isActive = false, isSynced = true, isAutoScrollEnabled = true, isSelectionMode = false), 0f)
        assertEquals(0.90f, computeAppleDistanceScale(4, isActive = false, isSynced = true, isAutoScrollEnabled = true, isSelectionMode = false), 0f)
        assertEquals(0.85f, computeAppleDistanceScale(6, isActive = false, isSynced = true, isAutoScrollEnabled = true, isSelectionMode = false), 0f)
    }

    @Test
    fun `formatWrittenByAnnotated bolds label prefix and keeps writer names regular`() {
        val annotated = formatWrittenByAnnotated("Written by Alice, Bob", listOf("Alice", "Bob"))
        assertEquals("Written by Alice, Bob", annotated.text)
        assertEquals(2, annotated.spanStyles.size)
        assertEquals(FontWeight.Bold, annotated.spanStyles[0].item.fontWeight)
        assertEquals(FontWeight.Normal, annotated.spanStyles[1].item.fontWeight)

        val localizedColon = formatWrittenByAnnotated("作词：张三, 李四", listOf("张三", "李四"))
        assertEquals(2, localizedColon.spanStyles.size)
        assertEquals("作词：", localizedColon.text.substring(localizedColon.spanStyles[0].start, localizedColon.spanStyles[0].end))
    }

    @Test
    fun `shouldFetchSongwriters excludes podcasts, Open RSS episodes, local files, and uploads`() {
        val baseSong = MediaMetadata(
            id = "dQw4w9WgXcQ",
            title = "Song",
            artists = emptyList(),
            duration = 200,
        )
        assertTrue(shouldFetchSongwriters(baseSong))
        assertFalse(shouldFetchSongwriters(null))
        assertFalse(shouldFetchSongwriters(baseSong.copy(isEpisode = true)))
        assertFalse(shouldFetchSongwriters(baseSong.copy(id = "rss_episode_123")))
        assertFalse(shouldFetchSongwriters(baseSong.copy(mediaUrl = "https://example.com/pod.mp3")))
        assertFalse(shouldFetchSongwriters(baseSong.copy(uploadEntityId = "upload_1")))
        assertFalse(shouldFetchSongwriters(baseSong.copy(id = "local_42")))
        assertFalse(shouldFetchSongwriters(baseSong.copy(id = "  ")))
    }

    @Test
    fun `resolveLyricsListAnchorIndex skips trailing Provider footer`() {
        val items = listOf(
            LyricsListItem.Line(1, LyricsEntry(1000L, "First")),
            LyricsListItem.Provider("YouTube", listOf("Author")),
        )
        assertEquals(0, resolveLyricsListAnchorIndex(items, 5))
    }

    @Test
    fun `word animation timing clamps short durations to minimum 100ms`() {
        val shortWord = WordTimestamp("Hi", 1.0, 1.01)
        val range = lyricsWordAnimationRangeMs(listOf(shortWord))
        assertEquals(1000L..1100L, range)
        assertEquals(0.5f, lyricsWordAnimationProgress(1050L, 1000L, 1010L), 0.001f)
    }
}

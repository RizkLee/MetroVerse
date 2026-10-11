/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsScrollTargetTest {
    private val mainLines = (0..8).toSet()

    @Test
    fun `backward seek missed by the preview rebases the anchor and subsequent following`() {
        val old = LyricsScrollTargetState(5, 5, setOf(5))
        val restored = resolveLyricsScrollTarget(old, setOf(1), mainLines, 60_000, 15_000, false)
        assertEquals(1, restored.index)
        assertEquals(1, restored.lastMainIndex)
        val next = resolveLyricsScrollTarget(restored, setOf(2), mainLines, 15_000, 25_000, false)
        assertEquals(2, next.index)
        val gap = resolveLyricsScrollTarget(next, emptySet(), mainLines, 25_000, 26_000, false)
        assertEquals(3, gap.index)
    }

    @Test
    fun `paused backward seek aligns without waiting for time to advance`() {
        val restored = resolveLyricsScrollTarget(LyricsScrollTargetState(6, 6, setOf(6)),
            setOf(2), mainLines, 60_000, 20_000, false)
        assertEquals(2, restored.index)
        val paused = resolveLyricsScrollTarget(restored, setOf(2), mainLines, 20_000, 20_000, false)
        assertEquals(2, paused.index)
    }

    @Test
    fun `repeated previews in both directions cannot leave a future anchor behind`() {
        var state = resolveLyricsScrollTarget(LyricsScrollTargetState(4, 4), setOf(7), mainLines,
            40_000, 40_000, true)
        assertEquals(7, state.index)
        state = resolveLyricsScrollTarget(state, setOf(1), mainLines, 40_000, 40_000, true)
        assertEquals(1, state.index)
        state = resolveLyricsScrollTarget(state, setOf(1), mainLines, 40_000, 10_000, false)
        state = resolveLyricsScrollTarget(state, setOf(2), mainLines, 10_000, 20_000, false)
        assertEquals(2, state.index)
    }

    @Test
    fun `clicked line retains priority while the player still reports an old line`() {
        val state = resolveLyricsScrollTarget(LyricsScrollTargetState(5, 5), setOf(5), mainLines,
            50_000, 50_000, true, pendingSeekIndex = 1)
        assertEquals(1, state.index)
        assertEquals(1, state.lastMainIndex)
    }

    @Test
    fun `normal overlapping singers retain the current anchor until that line ends`() {
        val original = LyricsScrollTargetState(2, 2, setOf(2))
        val overlapping = resolveLyricsScrollTarget(original, setOf(2, 3), mainLines, 20_000, 21_000, false)
        assertEquals(2, overlapping.index)
        val next = resolveLyricsScrollTarget(overlapping, setOf(3), mainLines, 21_000, 22_000, false)
        assertEquals(3, next.index)
    }

    @Test
    fun `an older overlapping singer cannot cause backward scrolling during normal playback`() {
        val state = resolveLyricsScrollTarget(LyricsScrollTargetState(3, 3, setOf(2, 3)),
            setOf(2), mainLines, 30_000, 31_000, false)
        assertEquals(3, state.index)
    }

    @Test
    fun `backward seek into an instrumental gap clears the old maximum`() {
        val state = resolveLyricsScrollTarget(LyricsScrollTargetState(6, 6, setOf(6)),
            emptySet(), mainLines, 60_000, 25_000, false, fallbackAnchorIndex = 2)
        assertEquals(2, state.index)
        assertEquals(2, state.lastMainIndex)
        assertEquals(3, resolveLyricsScrollTarget(state, setOf(3), mainLines, 25_000, 30_000, false).index)
    }

    @Test
    fun `alignment resolves the currently sung main line and its following gap`() {
        val lines = listOf(
            line(0, 8),
            line(3_000, 9, background = true),
            line(10_000, 15),
            line(20_000, 25),
        )
        assertEquals(2, findLyricsScrollAnchor(lines, 12_000))
        assertEquals(0, findLyricsScrollAnchor(lines, 8_500))
        assertEquals(2, findLyricsScrollAnchor(lines, 17_000))
        assertEquals(0, findLyricsScrollAnchor(lines, -1_000))
        assertEquals(-1, findLyricsScrollAnchor(emptyList(), 12_000))
    }

    @Test
    fun `short blank LRC gap keeps the previous visible line then advances to the next`() {
        val lines = listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + LyricsUtils.parseLyrics(
            "[00:00.00]First\n[00:10.00]Current\n[00:12.00]\n[00:14.00]Next",
        )
        val items = lines.mapIndexedNotNull { index, entry ->
            if (entry.text.isNotBlank()) LyricsListItem.Line(index, entry) else null
        }
        val active = LyricsUtils.findActiveLineIndices(lines, 13_000)
        assertEquals(setOf(3), active)
        assertEquals(1, resolveLyricsListAnchorIndex(items, active.single()))
        assertEquals(2, resolveLyricsListAnchorIndex(items, 4))
    }

    @Test
    fun `long gap retains its actual indicator row`() {
        val items = listOf(
            LyricsListItem.Line(1, line(0, 8)),
            LyricsListItem.Indicator(2, 8_000, 10_000, 18_000, null),
            LyricsListItem.Line(3, line(18_000, 25)),
        )
        assertEquals(1, resolveLyricsListAnchorIndex(items, 2))
        assertEquals(2, resolveLyricsListAnchorIndex(items, 3))
    }

    @Test
    fun `initial blank timestamp and empty content have safe anchors`() {
        val items = listOf(LyricsListItem.Line(1, line(5_000, 8)))
        assertEquals(0, resolveLyricsListAnchorIndex(items, 0))
        assertEquals(-1, resolveLyricsListAnchorIndex(emptyList(), 0))
    }

    private fun line(start: Long, endSeconds: Int, background: Boolean = false) =
        LyricsEntry(start, "line", listOf(WordTimestamp("line", start / 1000.0, endSeconds.toDouble())),
            isBackground = background)
}

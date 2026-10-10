/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsScrollGeometryTest {
    @Test
    fun `top pinned rows have no opposing animations when the anchor advances`() {
        val heights = List(8) { 80f }
        val gaps = List(8) { 12f }
        val anchorY = 350f
        for (active in 0..3) {
            val positions = computeLyricsItemPositions(heights, gaps, active)
            val rest = computeAutoRestOffset(-positions.getValue(0), positions.getValue(7), 80f, 600f, anchorY, 24f, 24f)
            assertEquals(24f, lyricsItemScreenOffset(anchorY, positions.getValue(0), rest), 0.01f)
            assertEquals(116f, lyricsItemScreenOffset(anchorY, positions.getValue(1), rest), 0.01f)
        }
    }

    @Test
    fun `background vocals and collapsed intervals keep the same spacing across anchors`() {
        val heights = listOf(160f, 72f, 0f, 280f, 120f)
        val gaps = listOf(0f, 0f, 0f, 16f, 16f)
        val absoluteStarts = listOf(0f, 160f, 232f, 248f, 544f)

        for (anchor in heights.indices) {
            val positions = computeLyricsItemPositions(heights, gaps, anchor)
            for (index in heights.indices) {
                assertEquals(absoluteStarts[index], positions.getValue(index) + absoluteStarts[anchor], 0.01f)
            }
        }
    }

    @Test
    fun `first and last lines remain reachable after playback moves the anchor`() {
        val heights = List(24) { if (it % 3 == 0) 280f else 140f }
        val gaps = List(heights.size) { 16f }
        val anchorY = 560f

        for (anchor in listOf(0, 12, heights.lastIndex)) {
            val positions = computeLyricsItemPositions(heights, gaps, anchor)
            val bounds = boundsFor(positions, heights.last())

            assertEquals(100f, anchorY + positions.getValue(0) + bounds.clampMax, 0.01f)
            assertEquals(1_470f, anchorY + positions.getValue(heights.lastIndex) + heights.last() + bounds.clampMin, 0.01f)
        }
    }

    @Test
    fun `a translation growing above the anchor expands the reachable start without moving the end`() {
        val heights = MutableList(24) { 140f }
        val gaps = List(heights.size) { 16f }
        val before = boundsFor(computeLyricsItemPositions(heights, gaps, 12), heights.last())

        heights[3] += 220f
        val positions = computeLyricsItemPositions(heights, gaps, 12)
        val after = boundsFor(positions, heights.last())

        assertEquals(before.clampMax + 220f, after.clampMax, 0.01f)
        assertEquals(before.clampMin, after.clampMin, 0.01f)
        assertEquals(100f, 560f + positions.getValue(0) + after.clampMax, 0.01f)
    }

    @Test
    fun `a short lyric list and an empty list have finite geometry`() {
        assertTrue(computeLyricsItemPositions(emptyList(), emptyList(), 0).isEmpty())
        assertEquals(mapOf(0 to 0f), computeLyricsItemPositions(listOf(160f), listOf(16f), 0))
    }

    private fun boundsFor(positions: Map<Int, Float>, lastHeight: Float): LyricsScrollBounds =
        computeLyricsScrollBounds(
            contentAbove = -positions.getValue(0),
            contentBelow = positions.getValue(positions.size - 1),
            lastItemHeight = lastHeight,
            viewportHeight = 1_600f,
            anchorY = 560f,
            topPadding = 100f,
            bottomPadding = 130f,
        )
}

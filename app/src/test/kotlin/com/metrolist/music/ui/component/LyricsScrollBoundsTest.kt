/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsScrollBoundsTest {

    private val viewport = 2000f
    private val anchorY = 700f
    private val topPadding = 100f
    private val bottomPadding = 130f

    @Test
    fun `maxOffset places the first lyric at the top padding`() {
        val bounds = computeLyricsScrollBounds(
            contentAbove = 500f,
            contentBelow = 1200f,
            lastItemHeight = 180f,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )

        val firstY = anchorY - 500f + bounds.maxOffset
        assertEquals(topPadding, firstY, 0.01f)
    }

    @Test
    fun `minOffset places the last lyric bottom at viewport bottom padding`() {
        val bounds = computeLyricsScrollBounds(
            contentAbove = 500f,
            contentBelow = 1200f,
            lastItemHeight = 180f,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )

        val lastBottom = anchorY + 1200f + 180f + bounds.minOffset
        assertEquals(viewport - bottomPadding, lastBottom, 0.01f)
    }

    @Test
    fun `manual clamp is exactly the first and last line extremes`() {
        val bounds = computeLyricsScrollBounds(
            contentAbove = 3000f,
            contentBelow = 3000f,
            lastItemHeight = 160f,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )

        assertEquals(bounds.minOffset, bounds.clampMin, 0.01f)
        assertEquals(bounds.maxOffset, bounds.clampMax, 0.01f)
        assertTrue(bounds.clampMin < 0f && bounds.clampMax > 0f)
    }

    @Test
    fun `auto rest is zero mid-song`() {
        val rest = computeAutoRestOffset(
            contentAbove = 3000f,
            contentBelow = 3000f,
            lastItemHeight = 160f,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )
        assertEquals(0f, rest, 0.01f)
    }

    @Test
    fun `auto rest pins the last line to the bottom near the end`() {
        // active == last line: nothing below
        val rest = computeAutoRestOffset(
            contentAbove = 3000f,
            contentBelow = 0f,
            lastItemHeight = 160f,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )
        val lastBottom = anchorY + 0f + 160f + rest
        assertEquals(viewport - bottomPadding, lastBottom, 0.01f)
        assertTrue("rest should be positive when pinning to bottom", rest > 0f)
    }

    @Test
    fun `auto rest pins the first line to the top near the start`() {
        val rest = computeAutoRestOffset(
            contentAbove = 200f,
            contentBelow = 3000f,
            lastItemHeight = 160f,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )
        val firstY = anchorY - 200f + rest
        assertEquals(topPadding, firstY, 0.01f)
        assertTrue("rest should be negative when pinning to top", rest < 0f)
    }

    @Test
    fun `auto rest never leaves the legal scroll range`() {
        val cases = listOf(
            Triple(3000f, 3000f, 160f),
            Triple(200f, 200f, 160f),
            Triple(800f, 800f, 160f),
            Triple(400f, 1200f, 160f),
            Triple(0f, 0f, 120f),
            Triple(3000f, 0f, 160f),
        )
        for ((above, below, lastH) in cases) {
            val bounds = computeLyricsScrollBounds(
                contentAbove = above,
                contentBelow = below,
                lastItemHeight = lastH,
                viewportHeight = viewport,
                anchorY = anchorY,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
            )
            val rest = computeAutoRestOffset(
                contentAbove = above,
                contentBelow = below,
                lastItemHeight = lastH,
                viewportHeight = viewport,
                anchorY = anchorY,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
            )
            assertTrue(
                "rest $rest outside [${bounds.clampMin}, ${bounds.clampMax}] for above=$above below=$below",
                rest >= bounds.clampMin - 0.01f && rest <= bounds.clampMax + 0.01f,
            )
        }
    }

    @Test
    fun `near the end the blank below the last line is not scrollable`() {
        val above = 3000f
        val below = 0f
        val lastH = 160f
        val bounds = computeLyricsScrollBounds(
            contentAbove = above,
            contentBelow = below,
            lastItemHeight = lastH,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )
        val rest = computeAutoRestOffset(
            contentAbove = above,
            contentBelow = below,
            lastItemHeight = lastH,
            viewportHeight = viewport,
            anchorY = anchorY,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )
        // Most-toward-end offset is the same pin-to-bottom position — no room to drag
        // further into blank space below the last line.
        assertEquals(rest, bounds.clampMin, 0.01f)
    }
}

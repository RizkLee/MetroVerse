/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleViewportTest {
    @Test
    fun `active subtitles move up by two displayed lines`() {
        val originalAnchor = 600f * 0.34f
        val anchor = subtitleAnchorTopPx(600f, 27f, 32f)

        assertEquals(54f, originalAnchor - anchor, 0.001f)
    }

    @Test
    fun `larger fonts reserve more space below the current cue`() {
        val standard = subtitleAnchorTopPx(600f, 27f, 32f)
        val large = subtitleAnchorTopPx(600f, 54f, 32f)

        assertTrue(large < standard)
        assertEquals(54f, standard - large, 0.001f)
    }

    @Test
    fun `short viewports and large fonts keep the cue below the safe top inset`() {
        assertEquals(32f, subtitleAnchorTopPx(180f, 54f, 32f), 0.001f)
        assertEquals(0f, subtitleAnchorTopPx(0f, 54f, 32f), 0.001f)
    }

    @Test
    fun `a wrapped translation header leaves space above the active cue`() {
        val anchor = subtitleAnchorTopPx(400f, 54f, 32f, headerHeightPx = 96f, headerGapPx = 12f)

        assertEquals(140f, anchor, 0.001f)
    }

    @Test
    fun `an idle translation header leaves no extra gap`() {
        val withoutHeader = subtitleAnchorTopPx(400f, 54f, 32f)
        val idle = subtitleAnchorTopPx(400f, 54f, 32f, headerHeightPx = 0f, headerGapPx = 12f)

        assertEquals(withoutHeader, idle, 0.001f)
    }
}

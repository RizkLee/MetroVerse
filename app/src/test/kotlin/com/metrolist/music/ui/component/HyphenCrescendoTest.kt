/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HyphenCrescendoTest {

    @Test
    fun `layout delta never includes the post-group spring`() {
        val groupEndMs = 10_000L
        for (pOutMs in listOf(0L, 50L, 150L, 300L, 599L)) {
            val deltas = hyphenCrescendoDeltas(
                pos = 2,
                isLast = true,
                groupEndMs = groupEndMs,
                sungFactor = 1f,
                smoothPositionMs = groupEndMs + pOutMs,
            )
            // Smooth release of the base spread only — bounded by the steady-state base.
            assertTrue("layoutDelta must stay small, was ${deltas.layoutDelta}", abs(deltas.layoutDelta) <= 2 * 0.012f + 1e-4f)
        }
    }

    @Test
    fun `steady-state last segment keeps base spread in layout and spring in visual only`() {
        val deltas = hyphenCrescendoDeltas(
            pos = 3,
            isLast = true,
            groupEndMs = 5_000L,
            sungFactor = 1f,
            smoothPositionMs = 4_000L,
        )
        val base = 3 * 0.012f
        assertEquals(base, deltas.layoutDelta, 1e-4f)
        // visual = base + peakScale when p == 1 (springPart -> peakScale)
        assertTrue(deltas.visualDelta > deltas.layoutDelta)
    }

    @Test
    fun `non-last segments use the same layout and visual delta`() {
        val deltas = hyphenCrescendoDeltas(
            pos = 1,
            isLast = false,
            groupEndMs = 5_000L,
            sungFactor = 0.5f,
            smoothPositionMs = 4_500L,
        )
        assertEquals(deltas.layoutDelta, deltas.visualDelta, 1e-6f)
    }

    @Test
    fun `exit spring can go negative visually but layout stays non-negative`() {
        val groupEndMs = 8_000L
        for (pOut in 0..60) {
            val deltas = hyphenCrescendoDeltas(
                pos = 1,
                isLast = true,
                groupEndMs = groupEndMs,
                sungFactor = 1f,
                smoothPositionMs = groupEndMs + pOut * 10L,
            )
            assertTrue("layoutDelta must be >= 0, was ${deltas.layoutDelta}", deltas.layoutDelta >= -1e-4f)
        }
    }

    @Test
    fun `callers using the same inputs always agree on layout vs visual`() {
        val a = hyphenCrescendoDeltas(2, true, 10_000L, 0.7f, 9_500L)
        val b = hyphenCrescendoDeltas(2, true, 10_000L, 0.7f, 9_500L)
        assertEquals(a.layoutDelta, b.layoutDelta, 0f)
        assertEquals(a.visualDelta, b.visualDelta, 0f)
    }
}

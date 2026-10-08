/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class AsrSubtitleTimingTest {
    @Test
    fun `resume retains only finalized streams and keeps native times relative to the restart boundary`() {
        val saved = listOf(
            SubtitleCue(1_000, 3_000, "first stream"),
            SubtitleCue(301_000, 303_000, "unfinished stream snapshot"),
        )
        val transcript = AsrTranscriptAccumulator(initialSample = 300_000 * 16L, retainedCues = saved)
        transcript.update(1, "restored native stream", 1f, 2f, true)
        assertEquals(
            listOf(saved.first(), SubtitleCue(301_000, 303_000, "restored native stream")),
            transcript.snapshot(304_000 * 16L),
        )
    }

    @Test
    fun `unchanged stable transcript reuses its snapshot while recognition hypotheses change`() {
        val transcript = AsrTranscriptAccumulator()
        transcript.update(1, "iPhone and NASA", 0f, 1f, true)
        val first = transcript.snapshot(16_000)
        transcript.update(1, "iPhone and NASA", 0f, 1f, true)
        transcript.update(2, "still recognizing", 1f, 1f, false)
        assertSame(first, transcript.snapshot(32_000))
        assertEquals("iPhone and NASA", first.single().text)
    }

    @Test
    fun `clipping a long final phrase never discards its words`() {
        val transcript = AsrTranscriptAccumulator()
        val words = (1..40).joinToString(" ") { "word$it" }
        transcript.update(1, words, 0f, 40f, true)
        val snapshot = transcript.snapshot(30_000 * 16L)
        assertEquals(words, snapshot.joinToString(" ") { it.text })
        assertEquals(30_000L, snapshot.last().endMs)
    }

    @Test
    fun `unfinished hypothesis never receives a translation cue index`() {
        assertTrue(stableAsrCues(0, "a changing hypothesis", 1f, 2f, false).isEmpty())
        assertEquals(listOf(SubtitleCue(1_000, 3_000, "a final sentence")), stableAsrCues(0, "a final sentence", 1f, 2f, true))
    }

    @Test
    fun `stream rotation preserves episode time`() {
        assertEquals(listOf(SubtitleCue(301_500, 304_000, "next sentence")), stableAsrCues(300_000, "next sentence", 1.5f, 2.5f, true))
    }

    @Test
    fun `invalid or empty native results do not create cues`() {
        assertTrue(stableAsrCues(0, "", 0f, 1f, true).isEmpty())
        assertTrue(stableAsrCues(0, "text", Float.NaN, 1f, true).isEmpty())
        assertTrue(stableAsrCues(0, "text", 0f, Float.POSITIVE_INFINITY, true).isEmpty())
        assertTrue(stableAsrCues(0, "text", 0f, -1f, true).isEmpty())
    }

    @Test
    fun `final flush retains the last phrase and a reused native id appends after rotation`() {
        val transcript = AsrTranscriptAccumulator()
        transcript.update(1, "first", 0f, 2f, true)
        transcript.update(2, "tentative", 2f, 1f, false)
        assertEquals(listOf(SubtitleCue(0, 2_000, "first")), transcript.snapshot(48_000))
        // stop() returns the final phrase; its synthetic trailing silence is outside the input.
        transcript.update(2, "final tail", 2f, 2f, true)
        transcript.finishStream(48_000)
        transcript.update(1, "next stream", 0.5f, 1f, true)
        assertEquals(
            listOf(SubtitleCue(0, 2_000, "first"), SubtitleCue(2_000, 3_000, "final tail"), SubtitleCue(3_500, 4_500, "next stream")),
            transcript.snapshot(72_000),
        )
    }

    @Test
    fun `rotation waits for a natural boundary but has a bounded fallback`() {
        assertTrue(!shouldRotateAsrStream(299_999, false))
        assertTrue(shouldRotateAsrStream(300_000, false))
        assertTrue(!shouldRotateAsrStream(300_000, true))
        assertTrue(shouldRotateAsrStream(360_000, true))
    }
}

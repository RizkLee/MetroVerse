/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleCheckpointTest {
    private fun checkpoint(cues: List<SubtitleCue>, resume: Long = 0) = EpisodeTranscript(
        "episode", TranscriptSource.ON_DEVICE, cues, "en", isComplete = false,
        resumePositionMs = resume, mediaSourceFingerprint = "source", recognizerFingerprint = "model",
    )

    @Test
    fun `resume requires the same audio source and recognizer`() {
        val saved = checkpoint(listOf(SubtitleCue(310_000, 320_000, "saved")), 300_000)
        assertEquals(300_000, subtitleResumePosition(saved, "source", "model"))
        assertEquals(0, subtitleResumePosition(saved, "new-source", "model"))
        assertEquals(0, subtitleResumePosition(saved, "source", "new-model"))
        assertEquals(0, subtitleResumePosition(saved.copy(isComplete = true), "source", "model"))
    }

    @Test
    fun `catching up keeps later saved cues while replacing the newly stable prefix`() {
        val previous = checkpoint(listOf(SubtitleCue(0, 1_000, "old"), SubtitleCue(2_000, 3_000, "later")))
        val incoming = checkpoint(listOf(SubtitleCue(0, 1_000, "corrected")))
        val merged = mergeSubtitleCheckpoint(previous, incoming)
        assertEquals(listOf("corrected", "later"), merged.cues.map { it.text })
        assertEquals(listOf("old", "later"), previous.cues.map { it.text })
        assertNotEquals(subtitleCueFingerprint(previous.cues.first()), subtitleCueFingerprint(merged.cues.first()))
    }

    @Test
    fun `a finished stream replaces older text even if recognition no longer emits a trailing cue`() {
        val previous = checkpoint(listOf(SubtitleCue(0, 1_000, "first"), SubtitleCue(2_000, 3_000, "old tail")))
        val fresh = checkpoint(listOf(SubtitleCue(0, 1_000, "first")), resume = 4_000)
        assertEquals(fresh, mergeSubtitleCheckpoint(previous, fresh))
    }

    @Test
    fun `changing source cannot retain another recording's saved tail`() {
        val previous = checkpoint(listOf(SubtitleCue(2_000, 3_000, "different recording")))
        val incoming = checkpoint(emptyList()).copy(mediaSourceFingerprint = "changed")
        assertEquals(incoming, mergeSubtitleCheckpoint(previous, incoming))
    }

    @Test
    fun `full completion replaces all retained partial cues`() {
        val previous = checkpoint(listOf(SubtitleCue(2_000, 3_000, "old tail")))
        val complete = checkpoint(listOf(SubtitleCue(0, 1_000, "final"))).copy(isComplete = true, progressPercent = 100)
        assertEquals(complete, mergeSubtitleCheckpoint(previous, complete))
    }
}

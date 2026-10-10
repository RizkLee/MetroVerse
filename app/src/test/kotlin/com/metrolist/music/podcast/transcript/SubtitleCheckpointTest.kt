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
    fun `first minute starts from zero and later playback keeps its requested start`() {
        for (position in listOf(-1L, 0L, 8_000L, 45_000L, 59_999L)) {
            val fresh = recoverAsrProgress(null, "source", "model", position)
            assertEquals(0, fresh.positionMs)
            assertEquals(emptyList<AsrAudioRange>(), fresh.ranges)
        }
        assertEquals(60_000, recoverAsrProgress(null, "source", "model", 60_000).positionMs)
        assertEquals(600_000, recoverAsrProgress(null, "source", "model", 600_000).positionMs)
    }

    @Test
    fun `old cancelled streams resume after completed text instead of replaying the entire stream`() {
        val saved = checkpoint(
            listOf(SubtitleCue(610_000, 640_000, "first"), SubtitleCue(645_000, 680_000, "second")),
            600_000,
        ).copy(processedRanges = listOf(AsrAudioRange(0, 600_000)))
        val recovered = recoverAsrProgress(saved, "source", "model", 20_000)
        assertEquals(680_000, recovered.positionMs)
        assertEquals(listOf(AsrAudioRange(0, 600_000), AsrAudioRange(610_000, 640_000), AsrAudioRange(645_000, 680_000)), recovered.ranges)
    }

    @Test
    fun `later saved ranges do not drag a backfill cursor to the end of the episode`() {
        val saved = checkpoint(
            listOf(SubtitleCue(10_000, 20_000, "backfill"), SubtitleCue(600_000, 620_000, "later")), 0,
        ).copy(processedRanges = listOf(AsrAudioRange(600_000, 900_000)))
        assertEquals(20_000, recoverAsrProgress(saved, "source", "model", 0).positionMs)
    }

    @Test
    fun `first recognition starts at playback position without claiming the earlier audio`() {
        val recovery = recoverAsrProgress(null, "source", "model", 900_000)
        assertEquals(900_000, recovery.positionMs)
        assertEquals(emptyList<AsrAudioRange>(), recovery.ranges)
    }

    @Test
    fun `range checkpoints resume noncontiguous work while old checkpoints keep their safe prefix`() {
        val legacy = checkpoint(listOf(SubtitleCue(0, 1_000, "saved")), 300_000)
        assertEquals(listOf(AsrAudioRange(0, 300_000)), recoverAsrProgress(legacy, "source", "model", 0).ranges)
        val ranges = listOf(AsrAudioRange(600_000, 900_000))
        val sparse = legacy.copy(processedRanges = ranges, resumePositionMs = 900_000)
        assertEquals(listOf(AsrAudioRange(0, 1_000)) + ranges, recoverAsrProgress(sparse, "source", "model", 0).ranges)
        val freshFromMiddle = legacy.copy(processedRanges = emptyList(), resumePositionMs = 900_000)
        assertEquals(listOf(AsrAudioRange(0, 1_000)), recoverAsrProgress(freshFromMiddle, "source", "model", 0).ranges)
    }

    @Test
    fun `backfilling cannot append a second copy of later saved subtitles`() {
        val later = SubtitleCue(900_000, 905_000, "later")
        val previous = checkpoint(listOf(later)).copy(processedRanges = listOf(AsrAudioRange(900_000, 910_000)))
        val earlier = SubtitleCue(0, 5_000, "earlier")
        val incoming = previous.copy(cues = listOf(earlier, later))
        assertEquals(incoming, mergeSubtitleCheckpoint(previous, incoming))
    }

    @Test
    fun `resume requires the same audio source and recognizer`() {
        val saved = checkpoint(listOf(SubtitleCue(310_000, 320_000, "saved")), 300_000)
        assertEquals(300_000, subtitleResumePosition(saved, "source", "model"))
        assertEquals(0, subtitleResumePosition(saved, "new-source", "model"))
        assertEquals(0, subtitleResumePosition(saved, "source", "new-model"))
        assertEquals(0, subtitleResumePosition(saved.copy(isComplete = true), "source", "model"))
    }

    @Test
    fun `retrying a changed recording starts at playback without stale cues or coverage`() {
        val saved = checkpoint(listOf(SubtitleCue(310_000, 320_000, "previous recording")), 300_000).copy(
            recognizerFingerprint = "requires-regeneration",
            processedRanges = listOf(AsrAudioRange(0, 300_000)),
        )
        val recovery = recoverAsrProgress(saved, "source", "model", 60_000)
        assertEquals(60_000, recovery.positionMs)
        assertEquals(emptyList<SubtitleCue>(), recovery.cues)
        assertEquals(emptyList<AsrAudioRange>(), recovery.ranges)
        assertEquals("incompatible_checkpoint", recovery.reason)
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

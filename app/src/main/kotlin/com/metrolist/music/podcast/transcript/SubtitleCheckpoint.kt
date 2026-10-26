/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

internal data class AsrRecovery(
    val positionMs: Long,
    val ranges: List<AsrAudioRange>,
    val cues: List<SubtitleCue>,
    val reason: String,
)

internal fun recoverAsrProgress(
    checkpoint: EpisodeTranscript?,
    mediaSourceFingerprint: String,
    recognizerFingerprint: String,
    initialPositionMs: Long,
): AsrRecovery {
    val initialStart = if (initialPositionMs < 60_000L) 0L else initialPositionMs
    if (checkpoint == null) return AsrRecovery(initialStart, emptyList(), emptyList(), "fresh")
    if (checkpoint.mediaSourceFingerprint != mediaSourceFingerprint) {
        return AsrRecovery(initialStart, emptyList(), emptyList(), "source_changed")
    }
    if (!compatibleAsrCheckpoints(checkpoint.recognizerFingerprint, recognizerFingerprint) || checkpoint.isComplete) {
        return AsrRecovery(initialStart, emptyList(), emptyList(), "incompatible_checkpoint")
    }
    val position = checkpoint.resumePositionMs.coerceAtLeast(0)
    val ranges = checkpoint.processedRanges ?: if (position > 0) listOf(AsrAudioRange(0, position)) else emptyList()
    // Older streaming checkpoints saved completed text before committing its coverage. Keep
    // those sentences covered without claiming that the unknown gaps between them are silence.
    val cueRanges = checkpoint.cues.filter { it.startMs >= 0 && it.endMs > it.startMs }
        .map { AsrAudioRange(it.startMs, it.endMs) }
    val nextCoveredStart = ranges.filter { it.startMs > position }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
    val stableEnd = checkpoint.cues.filter { it.startMs >= position && it.endMs <= nextCoveredStart }
        .maxOfOrNull { it.endMs } ?: position
    return AsrRecovery(maxOf(position, stableEnd), mergeAsrRanges(ranges + cueRanges), checkpoint.cues, "saved_checkpoint")
}

internal fun subtitleResumePosition(
    checkpoint: EpisodeTranscript?,
    mediaSourceFingerprint: String,
    recognizerFingerprint: String,
): Long = checkpoint?.takeIf {
    !it.isComplete && it.mediaSourceFingerprint == mediaSourceFingerprint &&
        compatibleAsrCheckpoints(it.recognizerFingerprint, recognizerFingerprint)
}?.resumePositionMs?.coerceAtLeast(0) ?: 0

/** Re-recognition replaces its stable prefix while a saved later tail remains readable. */
internal fun mergeSubtitleCheckpoint(
    previous: EpisodeTranscript?,
    incoming: EpisodeTranscript,
): EpisodeTranscript {
    if (previous == null || previous.mediaSourceFingerprint != incoming.mediaSourceFingerprint ||
        previous.recognizerFingerprint != incoming.recognizerFingerprint || incoming.isComplete
    ) return incoming
    if (incoming.processedRanges != null) {
        // Range-aware recognition already merges stable cues by media time, including backfills.
        return incoming.copy(progressPercent = maxOf(previous.progressPercent, incoming.progressPercent).coerceAtMost(99))
    }
    val frontier = maxOf(incoming.resumePositionMs, incoming.cues.lastOrNull()?.endMs ?: 0)
    val retained = previous.cues.filter { it.startMs >= frontier }
    return incoming.copy(
        cues = if (retained.isEmpty()) incoming.cues else incoming.cues + retained,
        progressPercent = maxOf(previous.progressPercent, incoming.progressPercent).coerceAtMost(99),
    )
}

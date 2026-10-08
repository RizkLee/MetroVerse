/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

internal fun subtitleResumePosition(
    checkpoint: EpisodeTranscript?,
    mediaSourceFingerprint: String,
    recognizerFingerprint: String,
): Long = checkpoint?.takeIf {
    !it.isComplete && it.mediaSourceFingerprint == mediaSourceFingerprint &&
        it.recognizerFingerprint == recognizerFingerprint
}?.resumePositionMs?.coerceAtLeast(0) ?: 0

/** Re-recognition replaces its stable prefix while a saved later tail remains readable. */
internal fun mergeSubtitleCheckpoint(
    previous: EpisodeTranscript?,
    incoming: EpisodeTranscript,
): EpisodeTranscript {
    if (previous == null || previous.mediaSourceFingerprint != incoming.mediaSourceFingerprint || incoming.isComplete) return incoming
    val frontier = maxOf(incoming.resumePositionMs, incoming.cues.lastOrNull()?.endMs ?: 0)
    val retained = previous.cues.filter { it.startMs >= frontier }
    return incoming.copy(
        cues = if (retained.isEmpty()) incoming.cues else incoming.cues + retained,
        progressPercent = maxOf(previous.progressPercent, incoming.progressPercent).coerceAtMost(99),
    )
}

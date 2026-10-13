/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

internal data class RestoredSubtitle(val transcript: EpisodeTranscript, val hasPendingSave: Boolean)

/** Local cues are useful before feed I/O or model validation, but never across audio sources. */
internal suspend fun restoreEpisodeSubtitle(
    episodeId: String,
    sourceFingerprint: String?,
    pending: suspend (String) -> EpisodeTranscript?,
    cached: suspend (String) -> EpisodeTranscript?,
): RestoredSubtitle? {
    val unsaved = pending(episodeId)?.takeIf {
        it.episodeId == episodeId && it.source == TranscriptSource.ON_DEVICE &&
            it.mediaSourceFingerprint == sourceFingerprint
    }
    if (unsaved != null) return RestoredSubtitle(unsaved, hasPendingSave = true)
    val saved = cached(episodeId)?.takeIf {
        it.episodeId == episodeId && (it.source == TranscriptSource.FEED ||
            it.mediaSourceFingerprint == sourceFingerprint || (it.isComplete && it.mediaSourceFingerprint == null))
    }
    return saved?.let { RestoredSubtitle(it, hasPendingSave = false) }
}

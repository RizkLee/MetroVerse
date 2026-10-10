/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

internal data class LyricsPlaybackPosition(
    val positionMs: Long,
    val pendingSeek: LyricsSeekTarget?,
    val isSeeking: Boolean,
)

/** A preview exists only during scrubbing; ordinary (including paused) playback stays separate. */
internal fun resolveLyricsPlaybackPosition(
    observedPositionMs: Long,
    previewPositionMs: Long?,
    observedIndex: Int,
    requestedSeek: LyricsSeekTarget?,
    nowMs: Long,
): LyricsPlaybackPosition {
    val pending = requestedSeek?.takeIf { previewPositionMs == null && it.isPending(observedIndex, nowMs) }
    return LyricsPlaybackPosition(
        previewPositionMs ?: pending?.positionMs ?: observedPositionMs,
        pending,
        previewPositionMs != null || pending != null,
    )
}

/** A clicked line owns the scroll target while the player is still reporting its old position. */
internal data class LyricsSeekTarget(
    val index: Int,
    val positionMs: Long,
    val requestedAtMs: Long,
) {
    fun isPending(playbackIndex: Int, nowMs: Long): Boolean =
        playbackIndex != index && nowMs - requestedAtMs < 2_000L
}

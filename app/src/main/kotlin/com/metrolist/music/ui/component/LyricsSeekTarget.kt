/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import kotlin.math.abs

internal const val LOCAL_LYRICS_EXTRAPOLATION_MAX_MS = 32L
internal const val CAST_LYRICS_EXTRAPOLATION_MAX_MS = 1_000L
internal const val LOCAL_LYRICS_MAX_NORMAL_STEP_MS = 500L
internal const val CAST_LYRICS_MAX_NORMAL_STEP_MS = 1_500L
internal const val LYRICS_SEEK_CONFIRM_WINDOW_MS = 750L
internal const val LYRICS_SEEK_BOUNDARY_TOLERANCE_MS = 100L

internal data class LyricsPlaybackClockState(
    val lastRawPlayerPosMs: Long,
    val lastSampleWallMs: Long,
    val awaitingAdvanceAfterSeek: Boolean = false,
)

internal data class LyricsObservedPositionResult(
    val observedPositionMs: Long,
    val clockState: LyricsPlaybackClockState,
)

/**
 * Samples the player playback clock without extrapolating forward while a seek or AudioTrack
 * refill is still settling, and bounds local interpolation so animation never outruns audio.
 */
internal fun sampleLyricsObservedPosition(
    state: LyricsPlaybackClockState,
    rawPlayerPosMs: Long,
    sliderPositionMs: Long?,
    nowMs: Long,
    playbackSpeed: Float,
    isActivelyPlaying: Boolean,
    isBuffering: Boolean,
    isCasting: Boolean,
    hasPendingSeek: Boolean,
    seekTriggered: Boolean,
): LyricsObservedPositionResult {
    val clampedRaw = rawPlayerPosMs.coerceAtLeast(0L)
    val deltaFromLast = clampedRaw - state.lastRawPlayerPosMs
    val maxNormalStepMs = if (isCasting) CAST_LYRICS_MAX_NORMAL_STEP_MS else LOCAL_LYRICS_MAX_NORMAL_STEP_MS
    val jumped = clampedRaw < state.lastRawPlayerPosMs || deltaFromLast > maxNormalStepMs
    val advancedNormally =
        clampedRaw > state.lastRawPlayerPosMs &&
            deltaFromLast <= maxNormalStepMs &&
            isActivelyPlaying &&
            !isBuffering &&
            !hasPendingSeek &&
            sliderPositionMs == null
    val nextAwaitingAdvance =
        when {
            seekTriggered || sliderPositionMs != null || isBuffering || hasPendingSeek || jumped -> true
            advancedNormally -> false
            else -> state.awaitingAdvanceAfterSeek
        }
    val updatedWall = if (clampedRaw != state.lastRawPlayerPosMs) nowMs else state.lastSampleWallMs
    val nextState =
        LyricsPlaybackClockState(
            lastRawPlayerPosMs = clampedRaw,
            lastSampleWallMs = updatedWall,
            awaitingAdvanceAfterSeek = nextAwaitingAdvance,
        )
    val observedPositionMs =
        when {
            sliderPositionMs != null -> sliderPositionMs
            !isActivelyPlaying || isBuffering || hasPendingSeek || nextAwaitingAdvance -> clampedRaw
            else -> {
                val maxExtrapolateMs =
                    if (isCasting) CAST_LYRICS_EXTRAPOLATION_MAX_MS else LOCAL_LYRICS_EXTRAPOLATION_MAX_MS
                val elapsedMs = (nowMs - updatedWall).coerceIn(0L, maxExtrapolateMs)
                clampedRaw + (elapsedMs * playbackSpeed.coerceAtLeast(0.1f)).toLong()
            }
        }
    return LyricsObservedPositionResult(observedPositionMs = observedPositionMs, clockState = nextState)
}

internal fun isLyricsSeekConfirmed(
    observedPositionMs: Long,
    target: LyricsSeekTarget,
    isBuffering: Boolean,
    activeIndicesAtObserved: Set<Int>,
): Boolean {
    if (isBuffering) return false
    if (target.index in activeIndicesAtObserved && abs(observedPositionMs - target.positionMs) <= LYRICS_SEEK_CONFIRM_WINDOW_MS) {
        return true
    }
    return observedPositionMs in (target.positionMs - LYRICS_SEEK_BOUNDARY_TOLERANCE_MS)..(target.positionMs + 250L)
}

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

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

fun shouldSkipAfterPlaybackFailure(
    autoSkipNextOnError: Boolean,
    wantsPlayback: Boolean,
    networkConnected: Boolean,
    repeatOne: Boolean,
    isNetworkOrTimeoutFailure: Boolean,
): Boolean =
    autoSkipNextOnError &&
        wantsPlayback &&
        networkConnected &&
        !repeatOne &&
        !isNetworkOrTimeoutFailure

class PlaybackFailureSkipBudget {
    private var consecutiveSkips = 0
    private var lastFailureToken: Long? = null
    private var healthyToken: String? = null
    private var progressMediaId: String? = null
    private var progressStartMs = 0L

    fun tryAdvance(failureToken: Long): Boolean {
        if (lastFailureToken == failureToken || consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
            return false
        }
        lastFailureToken = failureToken
        consecutiveSkips++
        return true
    }

    fun observeProgress(
        mediaId: String?,
        positionMs: Long,
    ) {
        if (mediaId == null || positionMs < 0L) return
        if (progressMediaId != mediaId || positionMs < progressStartMs) {
            progressMediaId = mediaId
            progressStartMs = positionMs
        }
        if (positionMs - progressStartMs >= HEALTHY_PROGRESS_RESET_MS && healthyToken != mediaId) {
            healthyToken = mediaId
            consecutiveSkips = 0
        }
    }

    fun reset() {
        consecutiveSkips = 0
        lastFailureToken = null
        healthyToken = null
        progressMediaId = null
        progressStartMs = 0L
    }

    companion object {
        const val MAX_CONSECUTIVE_SKIPS = 3
        const val HEALTHY_PROGRESS_RESET_MS = 30_000L
    }
}

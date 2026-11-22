/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

data class AudioOffloadEligibility(
    val offloadPref: Boolean,
    val crossfadeEnabled: Boolean,
    val skipSilenceEnabled: Boolean,
    val normalizationActive: Boolean,
    val equalizerActive: Boolean,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val sinkErrored: Boolean = false,
) {
    val canOffload: Boolean
        get() =
            offloadPref &&
                !sinkErrored &&
                !crossfadeEnabled &&
                !skipSilenceEnabled &&
                !normalizationActive &&
                !equalizerActive &&
                speed == 1f &&
                pitch == 1f
}

class OffloadSleepStallTracker(
    private val timeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
) {
    private var sleepingForOffload = false
    private var wasExpectingProgress = true
    private var lastProgressAtMs = 0L
    private var lastOutputPosition: Long? = null

    fun isSleeping(): Boolean = sleepingForOffload

    fun onSleepingForOffload(
        nowMs: Long,
        outputPosition: Long?,
    ) {
        if (sleepingForOffload) return
        sleepingForOffload = true
        lastProgressAtMs = nowMs
        lastOutputPosition = outputPosition
    }

    fun shouldDisableOffload(
        outputPosition: Long?,
        expectProgress: Boolean,
        nowMs: Long,
    ): Boolean {
        if (!sleepingForOffload) return false
        if (!expectProgress) {
            wasExpectingProgress = false
            lastProgressAtMs = nowMs
            return false
        }
        if (!wasExpectingProgress) {
            wasExpectingProgress = true
            lastProgressAtMs = nowMs
        }
        if (outputPosition == null || outputPosition != lastOutputPosition) {
            lastOutputPosition = outputPosition
            if (outputPosition != null) lastProgressAtMs = nowMs
            return false
        }
        if (nowMs - lastProgressAtMs < timeoutMs) return false
        reset()
        return true
    }

    fun reset() {
        sleepingForOffload = false
        wasExpectingProgress = true
        lastProgressAtMs = 0L
        lastOutputPosition = null
    }

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 2_000L
    }
}

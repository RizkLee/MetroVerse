/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.music.extensions.metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

internal const val SLEEP_TIMER_FADE_OUT_WINDOW_MS = 20_000L

internal fun sleepTimerVolumeMultiplier(remainingMs: Long): Float {
    if (remainingMs >= SLEEP_TIMER_FADE_OUT_WINDOW_MS) return 1f
    return (remainingMs.toFloat() / SLEEP_TIMER_FADE_OUT_WINDOW_MS).coerceIn(0f, 1f)
}

enum class SleepTimerMode {
    OFF,
    TIMED,
    END_OF_MEDIA,
    END_OF_QUEUE,
}

internal fun sleepTimerFadeOutEnabled(
    mode: SleepTimerMode,
    stopAfterCurrentSongOnTimeout: Boolean,
): Boolean = mode == SleepTimerMode.TIMED && !stopAfterCurrentSongOnTimeout

class SleepTimer(
    private val scope: CoroutineScope,
    var player: Player,
    private val onVolumeMultiplierChanged: (Float) -> Unit = {},
) : Player.Listener {
    private companion object {
        private const val TIMER_TICK_MS = 1000L
    }

    private var sleepTimerJob: Job? = null
    private var repeatModeBeforeEndTimer: Int? = null
    var triggerTime by mutableLongStateOf(-1L)
        private set
    var mode by mutableStateOf(SleepTimerMode.OFF)
        private set
    var stopAfterCurrentSongOnTimeout by mutableStateOf(false)
        private set

    val pauseWhenSongEnd: Boolean
        get() = mode == SleepTimerMode.END_OF_MEDIA
    val pauseWhenQueueEnd: Boolean
        get() = mode == SleepTimerMode.END_OF_QUEUE
    val isActive: Boolean
        get() = mode != SleepTimerMode.OFF
    val stopsAtPlaybackEnd: Boolean
        get() = mode == SleepTimerMode.END_OF_MEDIA || mode == SleepTimerMode.END_OF_QUEUE

    fun start(minute: Int) {
        start(
            minute = minute,
            stopAfterCurrentSong = false,
        )
    }

    fun start(
        minute: Int,
        stopAfterCurrentSong: Boolean,
    ) {
        if (minute == -1) {
            startAtEndOfCurrentMedia()
            return
        }

        resetTimerState()
        mode = SleepTimerMode.TIMED
        stopAfterCurrentSongOnTimeout = stopAfterCurrentSong
        triggerTime = System.currentTimeMillis() + minute.minutes.inWholeMilliseconds
        sleepTimerJob =
            scope.launch {
                while (mode == SleepTimerMode.TIMED) {
                    val remainingMs = triggerTime - System.currentTimeMillis()
                    if (remainingMs <= 0L) {
                        triggerTime = -1L
                        if (stopAfterCurrentSongOnTimeout) {
                            stopAfterCurrentSongOnTimeout = false
                            startEndMode(SleepTimerMode.END_OF_MEDIA)
                        } else {
                            completeTimerAndPause()
                        }
                        break
                    }

                    if (sleepTimerFadeOutEnabled(mode, stopAfterCurrentSongOnTimeout)) {
                        updateVolumeMultiplier(sleepTimerVolumeMultiplier(remainingMs))
                    }
                    delay(TIMER_TICK_MS)
                }
            }
    }

    fun startAtEndOfCurrentMedia() {
        startEndMode(SleepTimerMode.END_OF_MEDIA)
    }

    fun startAtEndOfQueue() {
        startEndMode(SleepTimerMode.END_OF_QUEUE)
    }

    private fun startEndMode(newMode: SleepTimerMode) {
        require(newMode == SleepTimerMode.END_OF_MEDIA || newMode == SleepTimerMode.END_OF_QUEUE)
        resetTimerState()
        repeatModeBeforeEndTimer = player.repeatMode
        player.repeatMode = Player.REPEAT_MODE_OFF
        mode = newMode
    }

    /**
     * Notify the sleep timer that a song transition has occurred outside of normal
     * player callbacks, for example during a crossfade player swap.
     */
    fun notifySongTransition() {
        if (mode == SleepTimerMode.END_OF_MEDIA) {
            completeTimerAndPause()
        }
    }

    fun clear() {
        resetTimerState()
        mode = SleepTimerMode.OFF
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (mode == SleepTimerMode.END_OF_MEDIA && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            completeTimerAndPause()
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        if (playbackState == Player.STATE_ENDED && stopsAtPlaybackEnd) {
            completeTimerAndPause()
        }
    }

    fun remainingTimeMs(): Long =
        when (mode) {
            SleepTimerMode.OFF -> 0L
            SleepTimerMode.TIMED -> (triggerTime - System.currentTimeMillis()).coerceAtLeast(0L)
            SleepTimerMode.END_OF_MEDIA -> currentMediaRemainingMs()
            SleepTimerMode.END_OF_QUEUE -> estimatedQueueRemainingMs()
        }

    private fun currentMediaRemainingMs(): Long {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return 0L
        return (duration - player.currentPosition).coerceAtLeast(0L)
    }

    private fun estimatedQueueRemainingMs(): Long {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return 0L

        var remainingMs = currentMediaRemainingMs()
        for (index in currentIndex + 1 until player.mediaItemCount) {
            val durationSeconds = player.getMediaItemAt(index).metadata?.duration ?: continue
            if (durationSeconds > 0) {
                remainingMs += durationSeconds * 1000L
            }
        }
        return remainingMs
    }

    private fun completeTimerAndPause() {
        resetTimerState()
        mode = SleepTimerMode.OFF
        player.pause()
    }

    private fun resetTimerState() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        repeatModeBeforeEndTimer?.let { player.repeatMode = it }
        repeatModeBeforeEndTimer = null
        stopAfterCurrentSongOnTimeout = false
        triggerTime = -1L
        updateVolumeMultiplier(1f)
    }

    private fun updateVolumeMultiplier(multiplier: Float) {
        onVolumeMultiplierChanged(multiplier)
    }
}

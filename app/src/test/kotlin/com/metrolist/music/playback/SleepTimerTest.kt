package com.metrolist.music.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SleepTimerTest {
    @Test
    fun `fade multiplier decreases linearly during final twenty seconds`() {
        assertEquals(1f, sleepTimerVolumeMultiplier(20_000L), 0.001f)
        assertEquals(0.5f, sleepTimerVolumeMultiplier(10_000L), 0.001f)
        assertEquals(0.25f, sleepTimerVolumeMultiplier(5_000L), 0.001f)
        assertEquals(0f, sleepTimerVolumeMultiplier(0L), 0.001f)
    }

    @Test
    fun `fade multiplier is clamped outside timer window`() {
        assertEquals(1f, sleepTimerVolumeMultiplier(30_000L), 0.001f)
        assertEquals(0f, sleepTimerVolumeMultiplier(-1_000L), 0.001f)
    }

    @Test
    fun `only direct countdown mode enables fade out`() {
        assertTrue(sleepTimerFadeOutEnabled(SleepTimerMode.TIMED, stopAfterCurrentSongOnTimeout = false))
        assertFalse(sleepTimerFadeOutEnabled(SleepTimerMode.TIMED, stopAfterCurrentSongOnTimeout = true))
        assertFalse(sleepTimerFadeOutEnabled(SleepTimerMode.END_OF_MEDIA, stopAfterCurrentSongOnTimeout = false))
        assertFalse(sleepTimerFadeOutEnabled(SleepTimerMode.END_OF_QUEUE, stopAfterCurrentSongOnTimeout = false))
    }

    @Test
    fun `timed remaining time tracks monotonic clock even when wall clock jumps`() {
        var monotonicMs = 10_000L
        var wallMs = 1_700_000_000_000L
        var crossfadeCancelled = 0
        val player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getCurrentMediaItemIndex" -> 0
                    "getRepeatMode" -> Player.REPEAT_MODE_OFF
                    "getShuffleModeEnabled" -> false
                    "getMediaItemCount" -> 1
                    else -> null
                }
            } as Player

        val timer =
            SleepTimer(
                scope = CoroutineScope(Dispatchers.Unconfined),
                player = player,
                onCancelCrossfade = { crossfadeCancelled++ },
                elapsedRealtimeMs = { monotonicMs },
                currentTimeMs = { wallMs },
            )

        timer.start(minute = 5, stopAfterCurrentSong = false)
        assertEquals(300_000L, timer.remainingTimeMs())

        // NTP/timezone jump backwards or forwards does not change monotonic remaining duration
        wallMs += 3_600_000L
        assertEquals(300_000L, timer.remainingTimeMs())

        monotonicMs += 120_000L
        assertEquals(180_000L, timer.remainingTimeMs())

        // Arming end-of-media cancels any active crossfade cleanly
        timer.startAtEndOfCurrentMedia()
        assertEquals(1, crossfadeCancelled)
        assertTrue(timer.pauseWhenSongEnd)
        timer.clear()
    }
}

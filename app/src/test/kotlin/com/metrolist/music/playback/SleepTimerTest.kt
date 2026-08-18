package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

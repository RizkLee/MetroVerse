package com.metrolist.music.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SleepTimerBehaviorTest {
    @Test
    fun `end of queue stops at the target occurrence and restores playback modes`() {
        val state = FakePlayerState(currentIndex = 1, repeatMode = Player.REPEAT_MODE_ALL, shuffleEnabled = true)
        val timer = SleepTimer(CoroutineScope(Dispatchers.Unconfined), state.player)

        timer.startAtEndOfQueue(boundaryIndex = 2)

        assertEquals(Player.REPEAT_MODE_OFF, state.repeatMode)
        assertFalse(state.shuffleEnabled)

        state.currentIndex = 2
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertFalse(state.paused)

        state.currentIndex = 3
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertTrue(state.paused)
        assertEquals(Player.REPEAT_MODE_ALL, state.repeatMode)
        assertTrue(state.shuffleEnabled)
        assertFalse(timer.isActive)
    }

    @Test
    fun `manual transition does not complete end of current item`() {
        val state = FakePlayerState(currentIndex = 0)
        val timer = state.createTimer()
        timer.startAtEndOfCurrentMedia()

        assertTrue(state.pauseAtEndOfMediaItems)

        state.currentIndex = 1
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertFalse(state.paused)
        assertTrue(timer.pauseWhenSongEnd)
        assertTrue(state.pauseAtEndOfMediaItems)
    }

    @Test
    fun `end of current item pauses at the media boundary`() {
        val state = FakePlayerState(currentIndex = 0)
        val timer = state.createTimer()
        timer.startAtEndOfCurrentMedia()

        timer.onPlayWhenReadyChanged(
            false,
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
        )

        assertTrue(state.paused)
        assertFalse(state.pauseAtEndOfMediaItems)
        assertFalse(timer.isActive)
    }

    @Test
    fun `end of queue enables precise pause only on boundary item`() {
        val state = FakePlayerState(currentIndex = 1)
        val timer = state.createTimer()
        timer.startAtEndOfQueue(boundaryIndex = 2)

        assertFalse(state.pauseAtEndOfMediaItems)
        assertFalse(timer.pausesAtCurrentMediaEnd)

        state.currentIndex = 2
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertTrue(state.pauseAtEndOfMediaItems)
        assertTrue(timer.pausesAtCurrentMediaEnd)

        timer.onPlayWhenReadyChanged(
            false,
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
        )

        assertTrue(state.paused)
        assertFalse(state.pauseAtEndOfMediaItems)
        assertFalse(timer.isActive)
    }

    private class FakePlayerState(
        var currentIndex: Int,
        var repeatMode: Int = Player.REPEAT_MODE_OFF,
        var shuffleEnabled: Boolean = false,
    ) {
        var paused: Boolean = false
        var pauseAtEndOfMediaItems: Boolean = false

        val player: Player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getCurrentMediaItemIndex" -> currentIndex
                    "getRepeatMode" -> repeatMode
                    "setRepeatMode" -> {
                        repeatMode = args!![0] as Int
                        Unit
                    }
                    "getShuffleModeEnabled" -> shuffleEnabled
                    "setShuffleModeEnabled" -> {
                        shuffleEnabled = args!![0] as Boolean
                        Unit
                    }
                    "pause" -> {
                        paused = true
                        Unit
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "FakePlayer"
                    else -> defaultValue(method.returnType)
                }
            } as Player

        fun createTimer(): SleepTimer =
            SleepTimer(
                scope = CoroutineScope(Dispatchers.Unconfined),
                player = player,
                onPauseAtEndOfMediaItemsChanged = { _, enabled ->
                    pauseAtEndOfMediaItems = enabled
                },
            )

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> 0.toChar()
                else -> null
            }
    }
}

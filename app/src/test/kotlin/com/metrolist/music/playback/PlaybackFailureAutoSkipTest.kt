/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureAutoSkipTest {
    @Test
    fun `shouldSkipAfterPlaybackFailure respects preference playback state network repeat and error kind`() {
        assertTrue(
            shouldSkipAfterPlaybackFailure(
                autoSkipNextOnError = true,
                wantsPlayback = true,
                networkConnected = true,
                repeatOne = false,
                isNetworkOrTimeoutFailure = false,
            ),
        )
        assertFalse(
            shouldSkipAfterPlaybackFailure(
                autoSkipNextOnError = false,
                wantsPlayback = true,
                networkConnected = true,
                repeatOne = false,
                isNetworkOrTimeoutFailure = false,
            ),
        )
        assertFalse(
            shouldSkipAfterPlaybackFailure(
                autoSkipNextOnError = true,
                wantsPlayback = false,
                networkConnected = true,
                repeatOne = false,
                isNetworkOrTimeoutFailure = false,
            ),
        )
        assertFalse(
            shouldSkipAfterPlaybackFailure(
                autoSkipNextOnError = true,
                wantsPlayback = true,
                networkConnected = false,
                repeatOne = false,
                isNetworkOrTimeoutFailure = false,
            ),
        )
        assertFalse(
            shouldSkipAfterPlaybackFailure(
                autoSkipNextOnError = true,
                wantsPlayback = true,
                networkConnected = true,
                repeatOne = true,
                isNetworkOrTimeoutFailure = false,
            ),
        )
        assertFalse(
            shouldSkipAfterPlaybackFailure(
                autoSkipNextOnError = true,
                wantsPlayback = true,
                networkConnected = true,
                repeatOne = false,
                isNetworkOrTimeoutFailure = true,
            ),
        )
    }

    @Test
    fun `skip budget allows max three consecutive skips rejects duplicate token and rearms after healthy progress`() {
        val budget = PlaybackFailureSkipBudget()

        assertTrue(budget.tryAdvance(1L))
        // Duplicate failure token cannot consume another advance
        assertFalse(budget.tryAdvance(1L))
        assertTrue(budget.tryAdvance(2L))
        assertTrue(budget.tryAdvance(3L))
        assertFalse(budget.tryAdvance(4L))

        // Less than 30s healthy progress does not rearm budget
        budget.observeProgress("song-a", 5_000L)
        budget.observeProgress("song-a", 34_999L)
        assertFalse(budget.tryAdvance(5L))

        // Crossing 30s healthy progress resets consecutive skip budget
        budget.observeProgress("song-a", 35_000L)
        assertTrue(budget.tryAdvance(6L))
    }
}

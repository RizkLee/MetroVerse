package com.metrolist.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionPolicyTest {
    @Test
    fun `podcast to song resets playback parameters`() {
        assertTrue(
            shouldResetPlaybackParameters(
                previousItemWasEpisode = true,
                currentItemIsEpisode = false,
            ),
        )
    }

    @Test
    fun `other media transitions retain playback parameters`() {
        assertFalse(shouldResetPlaybackParameters(previousItemWasEpisode = true, currentItemIsEpisode = true))
        assertFalse(shouldResetPlaybackParameters(previousItemWasEpisode = false, currentItemIsEpisode = false))
        assertFalse(shouldResetPlaybackParameters(previousItemWasEpisode = true, currentItemIsEpisode = null))
    }
}

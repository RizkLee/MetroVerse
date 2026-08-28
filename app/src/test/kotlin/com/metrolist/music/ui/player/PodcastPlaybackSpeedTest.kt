package com.metrolist.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastPlaybackSpeedTest {
    @Test
    fun `podcast speed options contain the requested discrete values`() {
        assertEquals(
            listOf(
                0.25f,
                0.5f,
                0.75f,
                1f,
                1.15f,
                1.25f,
                1.3f,
                1.4f,
                1.5f,
                1.6f,
                1.75f,
                2f,
                2.25f,
                2.5f,
                3f,
                4f,
            ),
            PODCAST_PLAYBACK_SPEEDS,
        )
        assertTrue(PODCAST_PLAYBACK_SPEEDS.containsAll(listOf(1.15f, 1.3f, 1.4f, 1.6f)))
        assertFalse(PODCAST_PLAYBACK_SPEEDS.any { it in listOf(2.75f, 3.25f, 3.5f, 3.75f) })
    }

    @Test
    fun `unknown current speed starts at the nearest option`() {
        assertEquals(PODCAST_PLAYBACK_SPEEDS.indexOf(1.15f), nearestPodcastPlaybackSpeedIndex(1.16f))
        assertEquals(PODCAST_PLAYBACK_SPEEDS.indexOf(3f), nearestPodcastPlaybackSpeedIndex(2.9f))
    }
}

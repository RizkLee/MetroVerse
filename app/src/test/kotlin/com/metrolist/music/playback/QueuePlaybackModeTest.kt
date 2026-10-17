package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePlaybackModeTest {
    @Test fun `queue button cycles sequential shuffle and persistent stop`() {
        var mode = QueuePlaybackMode.SEQUENTIAL
        mode = mode.next(allowStopAfterEach = true)
        assertEquals(QueuePlaybackMode.SHUFFLE, mode)
        mode = mode.next(allowStopAfterEach = true)
        assertEquals(QueuePlaybackMode.STOP_AFTER_EACH, mode)
        mode = mode.next(allowStopAfterEach = true)
        assertEquals(QueuePlaybackMode.SEQUENTIAL, mode)
    }

    @Test fun `cast keeps the existing two shuffle states`() {
        assertEquals(QueuePlaybackMode.SEQUENTIAL, QueuePlaybackMode.SHUFFLE.next(allowStopAfterEach = false))
    }

    @Test fun `the default queue button only cycles sequential and shuffle`() {
        var mode = QueuePlaybackMode.SEQUENTIAL
        repeat(3) {
            mode = mode.next()
            assertEquals(QueuePlaybackMode.SHUFFLE, mode)
            mode = mode.next()
            assertEquals(QueuePlaybackMode.SEQUENTIAL, mode)
        }
    }

    @Test fun `hiding stop after each removes it from display and preserves other pause reasons`() {
        val mode = QueuePlaybackMode.resolve(false, true)
        assertEquals(QueuePlaybackMode.SEQUENTIAL, mode)
        val queueRequestsPause = mode == QueuePlaybackMode.STOP_AFTER_EACH
        assertFalse(shouldPauseAtMediaBoundary(true, false, queueRequestsPause))
        assertTrue(shouldPauseAtMediaBoundary(false, false, queueRequestsPause))
        assertTrue(shouldPauseAtMediaBoundary(true, true, queueRequestsPause))
        assertEquals(QueuePlaybackMode.SHUFFLE, QueuePlaybackMode.resolve(true, true))
    }

    @Test fun `re-enabling the setting does not automatically activate stop after each`() {
        assertEquals(QueuePlaybackMode.SEQUENTIAL, QueuePlaybackMode.resolve(false, false, allowStopAfterEach = true))
        assertEquals(QueuePlaybackMode.SHUFFLE, QueuePlaybackMode.resolve(true, false, allowStopAfterEach = true))
    }

    @Test fun `queue pause survives sleep timer completion and autoplay changes`() {
        assertTrue(shouldPauseAtMediaBoundary(true, false, true))
        assertTrue(shouldPauseAtMediaBoundary(true, true, true))
        assertTrue(shouldPauseAtMediaBoundary(false, false, true))
        assertTrue(shouldPauseAtMediaBoundary(false, false, false))
        assertTrue(shouldPauseAtMediaBoundary(true, true, false))
        assertFalse(shouldPauseAtMediaBoundary(true, false, false))
        assertEquals(QueuePlaybackMode.STOP_AFTER_EACH, QueuePlaybackMode.resolve(true, true, allowStopAfterEach = true))
    }
}

/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtworkVideoCoordinatorAndWatchdogTest {
    @Before
    fun setUp() {
        ArtworkVideoCoordinator.resetForTest()
    }

    @Test
    fun `ArtworkVideoCoordinator prioritizes player over artist and restores previous owner on release`() {
        assertNull(ArtworkVideoCoordinator.activeOwnerId.value)

        ArtworkVideoCoordinator.acquire("artist_1", ArtworkVideoPriority.ARTIST)
        assertEquals("artist_1", ArtworkVideoCoordinator.activeOwnerId.value)

        ArtworkVideoCoordinator.acquire("player_1", ArtworkVideoPriority.PLAYER)
        assertEquals("player_1", ArtworkVideoCoordinator.activeOwnerId.value)

        ArtworkVideoCoordinator.acquire("artist_2", ArtworkVideoPriority.ARTIST)
        assertEquals("player_1", ArtworkVideoCoordinator.activeOwnerId.value)

        ArtworkVideoCoordinator.release("player_1")
        assertEquals("artist_2", ArtworkVideoCoordinator.activeOwnerId.value)

        ArtworkVideoCoordinator.release("artist_1")
        assertEquals("artist_2", ArtworkVideoCoordinator.activeOwnerId.value)

        ArtworkVideoCoordinator.release("artist_2")
        assertNull(ArtworkVideoCoordinator.activeOwnerId.value)
    }

    @Test
    fun `ArtworkVideoWatchdog triggers stall after 15 seconds without new frames and resets on new frame or pause`() {
        val watchdog = ArtworkVideoWatchdog()

        assertFalse(watchdog.isStalled(nowMs = 1_000L, renderedFrames = 1L, expectingFrames = true))
        assertFalse(watchdog.isStalled(nowMs = 15_999L, renderedFrames = 1L, expectingFrames = true))
        assertTrue(watchdog.isStalled(nowMs = 16_000L, renderedFrames = 1L, expectingFrames = true))

        assertFalse(watchdog.isStalled(nowMs = 16_500L, renderedFrames = 2L, expectingFrames = true))
        assertFalse(watchdog.isStalled(nowMs = 20_000L, renderedFrames = 2L, expectingFrames = false))
        assertFalse(watchdog.isStalled(nowMs = 34_000L, renderedFrames = 2L, expectingFrames = true))
        assertFalse(watchdog.isStalled(nowMs = 48_999L, renderedFrames = 2L, expectingFrames = true))
        assertTrue(watchdog.isStalled(nowMs = 49_000L, renderedFrames = 2L, expectingFrames = true))
    }

    @Test
    fun `computeAspectCropChildSize preserves aspect ratio for crop, fit, square, and invalid bounds`() {
        assertEquals(1778 to 1000, computeAspectCropChildSize(viewWidth = 1000, viewHeight = 1000, videoAspectRatio = 16f / 9f, centerCrop = true))
        assertEquals(1000 to 1333, computeAspectCropChildSize(viewWidth = 1000, viewHeight = 1000, videoAspectRatio = 0.75f, centerCrop = true))
        assertEquals(1000 to 1000, computeAspectCropChildSize(viewWidth = 1000, viewHeight = 1000, videoAspectRatio = 1f, centerCrop = true))

        assertEquals(1000 to 563, computeAspectCropChildSize(viewWidth = 1000, viewHeight = 1000, videoAspectRatio = 16f / 9f, centerCrop = false))
        assertEquals(750 to 1000, computeAspectCropChildSize(viewWidth = 1000, viewHeight = 1000, videoAspectRatio = 0.75f, centerCrop = false))

        assertEquals(1000 to 1000, computeAspectCropChildSize(viewWidth = 1000, viewHeight = 1000, videoAspectRatio = 0f, centerCrop = true))
        assertEquals(1000 to 1000, computeAspectCropChildSize(viewWidth = 1000, viewHeight = 1000, videoAspectRatio = Float.NaN, centerCrop = true))
        assertEquals(0 to 0, computeAspectCropChildSize(viewWidth = 0, viewHeight = 0, videoAspectRatio = 1.5f, centerCrop = true))
    }
}

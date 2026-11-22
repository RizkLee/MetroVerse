/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.artwork

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
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
    fun `computeTextureScaleMatrix applies center crop and center fit around view center`() {
        val cropWideValues = FloatArray(9)
        computeTextureScaleMatrix(
            viewWidth = 1000,
            viewHeight = 1000,
            videoWidth = 1600,
            videoHeight = 900,
            centerCrop = true,
        ).getValues(cropWideValues)
        assertEquals(16f / 9f, cropWideValues[android.graphics.Matrix.MSCALE_X], 0.001f)
        assertEquals(1f, cropWideValues[android.graphics.Matrix.MSCALE_Y], 0.001f)

        val fitWideValues = FloatArray(9)
        computeTextureScaleMatrix(
            viewWidth = 1000,
            viewHeight = 1000,
            videoWidth = 1600,
            videoHeight = 900,
            centerCrop = false,
        ).getValues(fitWideValues)
        assertEquals(1f, fitWideValues[android.graphics.Matrix.MSCALE_X], 0.001f)
        assertEquals(9f / 16f, fitWideValues[android.graphics.Matrix.MSCALE_Y], 0.001f)

        val cropTallValues = FloatArray(9)
        computeTextureScaleMatrix(
            viewWidth = 1000,
            viewHeight = 1000,
            videoWidth = 750,
            videoHeight = 1000,
            centerCrop = true,
        ).getValues(cropTallValues)
        assertEquals(1f, cropTallValues[android.graphics.Matrix.MSCALE_X], 0.001f)
        assertEquals(4f / 3f, cropTallValues[android.graphics.Matrix.MSCALE_Y], 0.001f)

        val squareValues = FloatArray(9)
        computeTextureScaleMatrix(
            viewWidth = 1000,
            viewHeight = 1000,
            videoWidth = 1080,
            videoHeight = 1080,
            centerCrop = true,
        ).getValues(squareValues)
        assertEquals(1f, squareValues[android.graphics.Matrix.MSCALE_X], 0.001f)
        assertEquals(1f, squareValues[android.graphics.Matrix.MSCALE_Y], 0.001f)
    }
}

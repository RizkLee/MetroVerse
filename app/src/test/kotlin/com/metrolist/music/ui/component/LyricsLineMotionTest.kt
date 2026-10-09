/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LyricsLineMotionTest {
    @Test
    fun `ordinary phone rows keep the full breathing amplitude`() {
        for (pivot in listOf(0f, 0.5f, 1f)) {
            assertEquals(1.025f, boundedLyricsLineScale(1.025f, 360f, 80f, 11f, 2f, pivot), 0f)
        }
    }

    @Test
    fun `wide rows stay inside both edges for left right and center alignment`() {
        for (pivot in listOf(0f, 0.5f, 1f)) {
            val peak = boundedLyricsLineScale(1.025f, 1_200f, 80f, 11f, 2f, pivot)
            val growth = (peak - 1f) * 1_200f
            assertTrue(peak > 1f)
            assertTrue(growth * pivot <= 11.001f)
            assertTrue(growth * (1f - pivot) <= 11.001f)
            val half = boundedLyricsLineScale(1.0125f, 1_200f, 80f, 11f, 2f, pivot)
            assertEquals((peak - 1f) / 2f, half - 1f, 1e-6f)
            assertEquals(1f, boundedLyricsLineScale(1f, 1_200f, 80f, 11f, 2f, pivot), 0f)
        }
    }

    @Test
    fun `tall wrapped lines keep their secondary lyrics clear`() {
        val scale = boundedLyricsLineScale(1.025f, 360f, 600f, 11f, 2f, 0.5f)
        assertTrue(scale > 1f)
        assertTrue((scale - 1f) * 600f / 2f <= 2.001f)
    }

    @Test
    fun `unmeasured or unpadded rows do not overflow`() {
        assertEquals(1f, boundedLyricsLineScale(1.025f, 0f, 80f, 11f, 2f, 0f), 0f)
        assertEquals(1f, boundedLyricsLineScale(1.025f, 360f, 0f, 11f, 2f, 0f), 0f)
        assertEquals(1f, boundedLyricsLineScale(1.025f, 360f, 80f, 0f, 2f, 0f), 0f)
    }

    @Test
    fun `the line grows once and settles without oscillating or shrinking below rest`() {
        val animation = TargetBasedAnimation(
            animationSpec = lyricsLineBreathingSpec(1f),
            typeConverter = Float.VectorConverter,
            initialValue = 1f,
            targetValue = 1f,
        )
        val samples = (0..1_000 step 5).map { timeMs ->
            animation.getValueFromNanos(timeMs * 1_000_000L)
        }

        assertEquals(1f, samples.first(), 0f)
        assertTrue(samples.max() > 1.02f)
        assertTrue(samples.all { it in 1f..1.0251f })
        val peakIndex = samples.indices.maxBy { samples[it] }
        assertTrue(samples.take(peakIndex + 1).zipWithNext().all { (a, b) -> b >= a })
        assertTrue(samples.drop(peakIndex).zipWithNext().all { (a, b) -> b <= a })
        assertEquals(1f, samples.last(), 0f)
    }

    @Test
    fun `playback updates do not replay the breath while a line stays highlighted`() = withComposition {
        isActive.value = true
        advanceBy(160)
        assertTrue(scale.value > 1.01f)
        advanceBy(1_000)
        assertEquals(1f, scale.value, 0f)

        repeat(20) {
            playbackPosition.longValue += 50L
            advanceBy(50)
            assertEquals(1f, scale.value, 0f)
        }
    }

    @Test
    fun `leaving the line settles its scale and seeking back starts a new breath`() = withComposition {
        isActive.value = true
        advanceBy(160)
        assertTrue(scale.value > 1.01f)

        isActive.value = false
        advanceBy(250)
        assertEquals(1f, scale.value, 0f)

        isActive.value = true
        advanceBy(160)
        assertTrue(scale.value > 1.01f)
    }

    @Test
    fun `background vocals and unsynced lines keep their existing size`() = withComposition {
        isBackground.value = true
        isActive.value = true
        repeat(100) {
            advanceBy(10)
            assertEquals(1f, scale.value, 0f)
        }

        isSynced.value = false
        isBackground.value = false
        repeat(100) {
            advanceBy(10)
            assertEquals(1f, scale.value, 0f)
        }
    }

    private fun withComposition(block: suspend MotionFixture.() -> Unit) = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val fixture = MotionFixture(frameClock)
        try {
            composition.setContent {
                // Reading playback here reproduces the parent's frequent recompositions.
                fixture.observedPosition = fixture.playbackPosition.longValue
                fixture.scale = rememberLyricsLineScale(
                    isSynced = fixture.isSynced.value,
                    isActiveLine = fixture.isActive.value,
                    isBackground = fixture.isBackground.value,
                )
            }
            fixture.advanceBy(20)
            fixture.block()
        } finally {
            composition.dispose()
            recomposer.cancel()
            recomposerJob.cancelAndJoin()
        }
    }

    private class MotionFixture(private val frameClock: BroadcastFrameClock) {
        val isSynced = mutableStateOf(true)
        val isActive = mutableStateOf(false)
        val isBackground = mutableStateOf(false)
        val playbackPosition = mutableLongStateOf(0L)
        var observedPosition = 0L
        lateinit var scale: State<Float>
        private var timeMs = 0L

        suspend fun advanceBy(durationMs: Long) {
            val targetTimeMs = timeMs + durationMs
            while (timeMs < targetTimeMs) {
                Snapshot.sendApplyNotifications()
                repeat(3) { yield() }
                timeMs = minOf(timeMs + 5L, targetTimeMs)
                frameClock.sendFrame(timeMs * 1_000_000L)
                repeat(3) { yield() }
            }
        }
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class StreamingPcmResamplerTest {
    @Test
    fun `ten minutes of 44100 Hz codec buffers have no cumulative sample loss`() {
        assertLongStreamCount(sourceRate = 44_100, sourceChunkFrames = 1152)
    }

    @Test
    fun `ten minutes of 48000 Hz codec buffers have no cumulative sample loss`() {
        assertLongStreamCount(sourceRate = 48_000, sourceChunkFrames = 1024)
    }

    @Test
    fun `44100 Hz interpolation is independent of codec buffer boundaries`() {
        val input = FloatArray(10_003) { (sin(it * 0.035) * 0.8).toFloat() }
        val expected = FloatArray(input.size * 16_000 / 44_100) { index ->
            val position = index.toLong() * 44_100
            val left = (position / 16_000).toInt()
            val fraction = (position % 16_000).toDouble() / 16_000
            val right = (left + 1).coerceAtMost(input.lastIndex)
            (input[left] + (input[right] - input[left]) * fraction).toFloat()
        }

        for (chunk in listOf(1, 13, 441, 1152, input.size)) {
            val resampler = StreamingPcmResampler(44_100, 1)
            val actual = mutableListOf<Float>()
            for (start in input.indices step chunk) {
                actual.addAll(resampler.process(input.copyOfRange(start, minOf(start + chunk, input.size))).toList())
            }
            actual.addAll(resampler.finish().toList())
            assertArrayEquals("chunk=$chunk", expected, actual.toFloatArray(), 0f)
        }
    }

    @Test
    fun `upsampling interpolates across buffers and repeats the final sample only at EOF`() {
        val resampler = StreamingPcmResampler(8_000, 1)
        val actual = mutableListOf<Float>()
        for (value in floatArrayOf(0f, 1f, 0f, -1f)) {
            actual.addAll(resampler.process(floatArrayOf(value)).toList())
        }
        assertEquals(7, actual.size)
        actual.addAll(resampler.finish().toList())
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, -0.5f, -1f, -1f), actual.toFloatArray(), 0f)
        assertTrue(resampler.finish().isEmpty())
    }

    @Test
    fun `float stereo frames split between buffers preserve channels`() {
        val resampler = StreamingPcmResampler(16_000, 2)
        val actual = resampler.process(floatArrayOf(1f, -1f, 0.25f)) +
            resampler.process(floatArrayOf(0.75f, -0.5f)) +
            resampler.process(floatArrayOf(-0.5f, 1f, 1f)) + resampler.finish()
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 1f), actual, 0f)
        assertEquals(4L, resampler.inputFrameCount)
    }

    @Test
    fun `PCM16 normalization and mono output are preserved`() {
        val resampler = StreamingPcmResampler(16_000, 1)
        val actual = resampler.process(shortArrayOf(Short.MIN_VALUE, 0)) +
            resampler.process(shortArrayOf(16384, Short.MAX_VALUE)) + resampler.finish()
        assertArrayEquals(floatArrayOf(-1f, 0f, 0.5f, 32767f / 32768f), actual, 0f)
    }

    @Test
    fun `a stream shorter than one output sample does not grow its duration`() {
        val resampler = StreamingPcmResampler(44_100, 1)
        assertTrue(resampler.process(shortArrayOf(16384)).isEmpty())
        assertTrue(resampler.finish().isEmpty())
        assertEquals(0L, resampler.outputSampleCount)
    }

    @Test(expected = IllegalStateException::class)
    fun `incomplete channel frame is rejected at EOF`() {
        val resampler = StreamingPcmResampler(16_000, 2)
        resampler.process(shortArrayOf(1))
        resampler.finish()
    }

    private fun assertLongStreamCount(sourceRate: Int, sourceChunkFrames: Int) {
        val resampler = StreamingPcmResampler(sourceRate, 1)
        val totalFrames = sourceRate.toLong() * 10 * 60
        val buffer = ShortArray(sourceChunkFrames)
        var remaining = totalFrames
        var actualSamples = 0L
        while (remaining > 0) {
            val count = minOf(remaining, sourceChunkFrames.toLong()).toInt()
            actualSamples += resampler.process(if (count == buffer.size) buffer else buffer.copyOf(count)).size
            remaining -= count
        }
        actualSamples += resampler.finish().size
        assertEquals(totalFrames, resampler.inputFrameCount)
        assertEquals(9_600_000L, actualSamples)
        assertEquals(actualSamples, resampler.outputSampleCount)
    }
}

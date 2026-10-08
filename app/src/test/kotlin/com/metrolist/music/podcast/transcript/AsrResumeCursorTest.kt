/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AsrResumeCursorTest {
    @Test
    fun `resume produces exactly the remaining PCM across irregular decoder chunks`() {
        val cursor = AsrResumeCursor(resumePositionMs = 5, sampleRate = 1_000)
        val audio = (0 until 12).map(Int::toFloat).toFloatArray()
        val emitted = listOf(
            audio.copyOfRange(0, 3),
            audio.copyOfRange(3, 8),
            audio.copyOfRange(8, 12),
        ).flatMap { cursor.remaining(it).toList() }.toFloatArray()
        cursor.verifyEndOfInput()
        assertArrayEquals(audio.copyOfRange(5, 12), emitted, 0f)
        assertEquals(12L, cursor.decodedSamples)
    }

    @Test
    fun `normal recognition retains the original PCM buffer without another copy`() {
        val cursor = AsrResumeCursor(0)
        val audio = floatArrayOf(0.25f, -0.25f)
        assertSame(audio, cursor.remaining(audio))
    }

    @Test(expected = IllegalStateException::class)
    fun `shortened audio cannot turn a checkpoint into a completed transcript`() {
        val cursor = AsrResumeCursor(10, sampleRate = 1_000)
        cursor.remaining(FloatArray(9))
        cursor.verifyEndOfInput()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid checkpoint cannot overflow the sample clock`() {
        AsrResumeCursor(Long.MAX_VALUE)
    }
}

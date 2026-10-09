/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * MetroVerse modifications (C) 2026 Rizklee
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.music.eq.audio.CustomEqualizerAudioProcessor
import com.metrolist.music.eq.data.ParametricEQ
import com.metrolist.music.eq.data.ParametricEQBand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
@Suppress("DEPRECATION")
class AudioProcessorBufferReuseTest {
    @Test
    fun `processors reuse direct storage without changing passthrough audio`() {
        for (processor in processors()) {
            configureProcessor(processor)
            val first = queueAndRead(processor, samples(4_096, 1))
            val second = queueAndRead(processor, samples(4_096, 37))

            assertTrue(first.first.isDirect)
            assertSame(first.first, second.first)
            assertArrayEquals(samples(4_096, 1), first.second)
            assertArrayEquals(samples(4_096, 37), second.second)
        }
    }

    @Test
    fun `larger chunks grow storage and shorter chunks never expose stale samples`() {
        for (processor in processors()) {
            configureProcessor(processor)
            val first = queueAndRead(processor, samples(64, 1))
            val grown = queueAndRead(processor, samples(4_096, 2))
            val shorter = queueAndRead(processor, samples(16, 3))

            assertNotSame(first.first, grown.first)
            assertSame(grown.first, shorter.first)
            assertArrayEquals(samples(4_096, 2), grown.second)
            assertArrayEquals(samples(16, 3), shorter.second)
        }
    }

    @Test
    fun `flush discards pending samples but retains storage and reset releases it`() {
        for (processor in processors()) {
            configureProcessor(processor)
            val first = queueAndRead(processor, samples(4_096, 1))
            processor.queueInput(inputBuffer(samples(64, 2)))
            processor.queueEndOfStream()
            processor.flush()
            assertEquals(0, processor.output.remaining())
            assertTrue(!processor.isEnded)

            val afterFlush = queueAndRead(processor, samples(32, 3))
            assertSame(first.first, afterFlush.first)
            assertArrayEquals(samples(32, 3), afterFlush.second)

            processor.reset()
            configureProcessor(processor)
            val afterReset = queueAndRead(processor, samples(32, 4))
            assertNotSame(first.first, afterReset.first)
            assertArrayEquals(samples(32, 4), afterReset.second)
        }
    }

    @Test
    fun `normalization gain and clipping survive reused and flushed storage`() {
        val processor = VolumeNormalizationAudioProcessor()
        configureProcessor(processor)
        processor.enabled = true
        processor.setTargetGain(600)
        queueAndRead(processor, ByteArray(4_096))

        val source = shortSamples(1_000, -1_000, Short.MAX_VALUE, Short.MIN_VALUE)
        val expected = shortSamples(1_995, -1_995, Short.MAX_VALUE, Short.MIN_VALUE)
        assertArrayEquals(expected, queueAndRead(processor, source).second)
        processor.flush()
        assertArrayEquals(expected, queueAndRead(processor, source).second)
    }

    @Test
    fun `equalizer filter continuity is independent of audio chunk boundaries`() {
        fun equalizer() = CustomEqualizerAudioProcessor().apply {
            configureProcessor(this)
            applyProfile(ParametricEQ(-3.0, listOf(ParametricEQBand(1_000.0, 4.0))))
        }
        val source = samples(4_096, 7)
        val expected = queueAndRead(equalizer(), source).second
        val splitProcessor = equalizer()
        val first = queueAndRead(splitProcessor, source.copyOfRange(0, 2_048))
        val second = queueAndRead(splitProcessor, source.copyOfRange(2_048, source.size))

        assertSame(first.first, second.first)
        assertArrayEquals(expected, first.second + second.second)
    }

    private fun processors(): List<AudioProcessor> = listOf(
        VolumeNormalizationAudioProcessor(),
        SilenceDetectorAudioProcessor(onLongSilence = {}),
        CustomEqualizerAudioProcessor(),
    )

    private fun configureProcessor(processor: AudioProcessor) {
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
    }

    private fun queueAndRead(processor: AudioProcessor, samples: ByteArray): Pair<ByteBuffer, ByteArray> {
        val input = inputBuffer(samples)
        processor.queueInput(input)
        assertEquals(0, input.remaining())
        val output = processor.output
        assertEquals(samples.size, output.remaining())
        return output to ByteArray(output.remaining()).also(output::get)
    }

    private fun inputBuffer(samples: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size).order(ByteOrder.nativeOrder()).apply {
            put(samples)
            flip()
        }

    private fun samples(size: Int, seed: Int): ByteArray = ByteArray(size) { (it + seed).toByte() }

    private fun shortSamples(vararg samples: Short): ByteArray =
        ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
        }.array()
}

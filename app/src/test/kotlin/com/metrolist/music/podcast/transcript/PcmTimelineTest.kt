/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTimelineTest {
    @Test
    fun `seeking starts on the absolute media clock rather than a fresh zero clock`() {
        val clock = PcmTimestampTracker()
        val position = clock.locate(1_810_000_000, 32_000, 16_000, 1)
        assertTrue(position.startsNewSegment)
        assertEquals(28_960_000L, position.segmentStartSample)
        assertEquals(28_992_000L, clock.endSample)
    }

    @Test
    fun `a five second media gap does not compress later timestamps`() {
        val clock = PcmTimestampTracker()
        clock.locate(0, 16_000, 16_000, 1)
        val afterGap = clock.locate(6_000_000, 16_000, 16_000, 1)
        assertTrue(afterGap.startsNewSegment)
        assertEquals(96_000L, afterGap.segmentStartSample)
        assertEquals(112_000L, clock.endSample)
        assertFalse(clock.locate(7_000_000, 16_000, 16_000, 1).startsNewSegment)
        assertEquals(128_000L, clock.endSample)
    }

    @Test
    fun `silent PCM keeps its full duration and does not reset the clock`() {
        val clock = PcmTimestampTracker()
        repeat(90) { second ->
            val position = clock.locate(second * 1_000_000L, 16_000, 16_000, 1)
            assertEquals(second == 0, position.startsNewSegment)
            assertEquals(0, position.skipFrames)
        }
        assertEquals(90L * 16_000, clock.endSample)
    }

    @Test
    fun `44100 Hz rounded buffer timestamps retain the same anchor over a long episode`() {
        val clock = PcmTimestampTracker()
        val startUs = 1_800_123_456L
        val totalFrames = 44_100L * 60 * 10
        var frame = 0L
        while (frame < totalFrames) {
            val count = minOf(1_152L, totalFrames - frame).toInt()
            val position = clock.locate(startUs + frame * 1_000_000L / 44_100, count, 44_100, 2)
            assertEquals(frame == 0L, position.startsNewSegment)
            assertEquals(0, position.skipFrames)
            frame += count
        }
        assertEquals(mediaTimeUsToPcmSample(startUs + 600_000_000L), clock.endSample)
    }

    @Test
    fun `duplicate decoder output is not counted twice`() {
        val clock = PcmTimestampTracker()
        clock.locate(0, 320, 16_000, 1)
        val duplicate = clock.locate(0, 320, 16_000, 1)
        assertEquals(320, duplicate.skipFrames)
        assertFalse(duplicate.startsNewSegment)
        assertEquals(320L, clock.endSample)
        assertEquals(0, clock.locate(20_000, 320, 16_000, 1).skipFrames)
        assertEquals(640L, clock.endSample)
    }

    @Test
    fun `only the overlapping prefix is removed when adjacent frames overlap`() {
        val clock = PcmTimestampTracker()
        clock.locate(0, 320, 16_000, 1)
        val overlap = clock.locate(15_000, 320, 16_000, 1)
        assertEquals(80, overlap.skipFrames)
        assertFalse(overlap.startsNewSegment)
        assertEquals(560L, clock.endSample)
    }

    @Test(expected = IllegalStateException::class)
    fun `a timestamp reset fails instead of shifting the rest of the episode`() {
        val clock = PcmTimestampTracker()
        clock.locate(1_800_000_000, 320, 16_000, 1)
        clock.locate(1_800_020_000, 320, 16_000, 1)
        clock.locate(0, 320, 16_000, 1)
    }

    @Test(expected = IllegalStateException::class)
    fun `a decoder with timestamps stuck at zero cannot silently discard the entire tail`() {
        val clock = PcmTimestampTracker()
        clock.locate(0, 320, 16_000, 1)
        repeat(33) { clock.locate(0, 320, 16_000, 1) }
    }

    @Test
    fun `a format change resets interpolation while retaining the media offset`() {
        val clock = PcmTimestampTracker()
        clock.locate(10_000_000, 320, 16_000, 1)
        val changed = clock.locate(10_020_000, 960, 48_000, 2)
        assertTrue(changed.startsNewSegment)
        assertEquals(160_320L, changed.segmentStartSample)
        assertEquals(160_640L, clock.endSample)
    }

    @Test
    fun `negative priming timestamps are retained until the requested range clips them`() {
        val clock = PcmTimestampTracker()
        val position = clock.locate(-10_000, 320, 16_000, 1)
        assertEquals(-160L, position.segmentStartSample)
        val chunks = mutableListOf<TimedPcmChunk>()
        val buffer = TimedPcmChunkBuffer(0, null, 320) { chunks.add(it) }
        buffer.append(FloatArray(320) { it.toFloat() }, position.segmentStartSample)
        buffer.finishSegment()
        assertEquals(1, chunks.size)
        assertEquals(0L, chunks.single().startSample)
        assertEquals(160, chunks.single().samples.size)
        assertEquals(160f, chunks.single().samples.first(), 0f)
    }

    @Test
    fun `requested bounds are half open and never include earlier or later PCM`() {
        val chunks = mutableListOf<TimedPcmChunk>()
        val buffer = TimedPcmChunkBuffer(100, 250, 64) { chunks.add(it) }
        assertFalse(buffer.append(FloatArray(320) { it.toFloat() }, 0))
        assertEquals(listOf(100L, 164L, 228L), chunks.map { it.startSample })
        assertEquals(listOf(64, 64, 22), chunks.map { it.samples.size })
        assertEquals(100f, chunks.first().samples.first(), 0f)
        assertEquals(249f, chunks.last().samples.last(), 0f)
        assertEquals(250L, buffer.emittedEndSample)
        assertTrue(buffer.reachedRangeEnd)
        assertFalse(buffer.stoppedEarly)
    }

    @Test
    fun `gaps flush a short chunk without padding or attaching later audio to it`() {
        val chunks = mutableListOf<TimedPcmChunk>()
        val buffer = TimedPcmChunkBuffer(0, null, 8) { chunks.add(it) }
        buffer.append(floatArrayOf(1f, 2f, 3f), 0)
        buffer.append(floatArrayOf(4f, 5f, 6f, 7f), 80_003)
        buffer.finishSegment()
        assertEquals(listOf(0L, 80_003L), chunks.map { it.startSample })
        assertEquals(listOf(3, 4), chunks.map { it.samples.size })
        assertEquals(80_007L, buffer.emittedEndSample)
    }

    @Test
    fun `a one sample resampling overlap is clipped at a new segment boundary`() {
        val chunks = mutableListOf<TimedPcmChunk>()
        val buffer = TimedPcmChunkBuffer(0, null, 8) { chunks.add(it) }
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f), 0)
        buffer.append(floatArrayOf(4f, 5f, 6f), 3)
        buffer.finishSegment()
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), chunks.single().samples, 0f)
        assertEquals(6L, buffer.emittedEndSample)
    }

    @Test
    fun `stopping a range releases pending chunks without calling the consumer again`() {
        var delivered = 0
        val buffer = TimedPcmChunkBuffer(0, null, 4) {
            delivered++
            false
        }
        assertFalse(buffer.append(FloatArray(20), 0))
        assertFalse(buffer.append(FloatArray(20), 20))
        assertFalse(buffer.finishSegment())
        assertEquals(1, delivered)
        assertEquals(4L, buffer.emittedEndSample)
        assertTrue(buffer.stoppedEarly)
        assertFalse(buffer.reachedRangeEnd)
    }

    @Test
    fun `a requested endpoint inside a media gap does not emit the following audio`() {
        val chunks = mutableListOf<TimedPcmChunk>()
        val buffer = TimedPcmChunkBuffer(0, 5, 8) { chunks.add(it) }
        buffer.append(floatArrayOf(1f, 2f, 3f), 0)
        assertFalse(buffer.append(floatArrayOf(4f, 5f, 6f), 10))
        assertTrue(buffer.reachedRangeEnd)
        assertEquals(3L, buffer.emittedEndSample)
        assertEquals(1, chunks.size)
    }

    @Test
    fun `empty input and a range beyond available PCM do not fabricate a chunk`() {
        var delivered = 0
        val buffer = TimedPcmChunkBuffer(100, null, 8) {
            delivered++
            true
        }
        assertTrue(buffer.append(FloatArray(0), 0))
        assertTrue(buffer.append(FloatArray(20), 0))
        assertTrue(buffer.finishSegment())
        assertEquals(0, delivered)
        assertEquals(null, buffer.emittedEndSample)
        assertFalse(buffer.reachedRangeEnd)
    }

    @Test
    fun `microsecond conversion rounds negative priming down rather than shifting it forward`() {
        assertEquals(-1L, mediaTimeUsToPcmSample(-1))
        assertEquals(0L, mediaTimeUsToPcmSample(0))
        assertEquals(1L, mediaTimeUsToPcmSample(63))
        assertEquals(16_000L, mediaTimeUsToPcmSample(1_000_000))
    }
}

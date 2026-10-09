/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import kotlin.math.abs

internal const val ASR_PCM_SAMPLE_RATE = 16_000

/** Positions use the original media timeline, including gaps that contain no decoded PCM. */
internal data class TimedPcmChunk(
    val samples: FloatArray,
    val startSample: Long,
) {
    val sampleRate: Int get() = ASR_PCM_SAMPLE_RATE
    val endSample: Long get() = startSample + samples.size
    val startMs: Long get() = startSample / (ASR_PCM_SAMPLE_RATE / 1_000)
    val endMs: Long get() = endSample / (ASR_PCM_SAMPLE_RATE / 1_000)
}

internal fun mediaTimeUsToPcmSample(timeUs: Long): Long =
    Math.floorDiv(timeUs, 1_000_000L) * ASR_PCM_SAMPLE_RATE +
        Math.floorMod(timeUs, 1_000_000L) * ASR_PCM_SAMPLE_RATE / 1_000_000L

/** Small timestamp rounding errors must not reset interpolation at every codec buffer. */
internal class PcmTimestampTracker {
    data class BufferPosition(
        val skipFrames: Int,
        val startsNewSegment: Boolean,
        val segmentStartSample: Long,
    )

    private var segmentStartUs = 0L
    private var segmentFrames = 0L
    private var sourceSampleRate = 0
    private var channelCount = 0
    private var lastBufferPtsUs: Long? = null
    private var consecutiveDuplicates = 0

    val endSample: Long get() = mediaTimeUsToPcmSample(expectedTimeUs())

    fun locate(
        presentationTimeUs: Long,
        frameCount: Int,
        sampleRate: Int,
        channels: Int,
    ): BufferPosition {
        require(frameCount > 0 && sampleRate > 0 && channels > 0)
        val previousPts = lastBufferPtsUs
        check(previousPts == null || presentationTimeUs >= previousPts - TIMESTAMP_TOLERANCE_US) {
            "Decoded audio timestamps moved backwards"
        }

        var skipFrames = 0
        val expectedUs = expectedTimeUs()
        if (previousPts != null && presentationTimeUs < expectedUs - TIMESTAMP_TOLERANCE_US) {
            val overlapUs = expectedUs - presentationTimeUs
            val bufferDurationUs = frameCount.toLong() * 1_000_000L / sampleRate
            skipFrames = if (overlapUs >= bufferDurationUs) {
                frameCount
            } else {
                ((overlapUs * sampleRate + 999_999L) / 1_000_000L).toInt().coerceAtMost(frameCount)
            }
            if (skipFrames == frameCount) {
                consecutiveDuplicates++
                check(consecutiveDuplicates <= MAX_CONSECUTIVE_DUPLICATES) {
                    "Decoded audio timestamps stopped advancing"
                }
                return BufferPosition(skipFrames, false, mediaTimeUsToPcmSample(segmentStartUs))
            }
        }

        val adjustedStartUs = presentationTimeUs + skipFrames.toLong() * 1_000_000L / sampleRate
        val startsNewSegment = previousPts == null ||
            sourceSampleRate != sampleRate || channelCount != channels ||
            abs(adjustedStartUs - expectedUs) > TIMESTAMP_TOLERANCE_US
        if (startsNewSegment) {
            segmentStartUs = if (previousPts == null) adjustedStartUs else maxOf(adjustedStartUs, expectedUs)
            segmentFrames = 0
            sourceSampleRate = sampleRate
            channelCount = channels
        }
        segmentFrames += frameCount - skipFrames
        lastBufferPtsUs = presentationTimeUs
        consecutiveDuplicates = 0
        return BufferPosition(skipFrames, startsNewSegment, mediaTimeUsToPcmSample(segmentStartUs))
    }

    private fun expectedTimeUs(): Long = if (sourceSampleRate == 0) 0L else
        segmentStartUs + segmentFrames / sourceSampleRate * 1_000_000L +
            segmentFrames % sourceSampleRate * 1_000_000L / sourceSampleRate

    private companion object {
        const val TIMESTAMP_TOLERANCE_US = 2_000L
        const val MAX_CONSECUTIVE_DUPLICATES = 32
    }
}

/** Bounded carry buffer: a chunk never spans a media timestamp discontinuity. */
internal class TimedPcmChunkBuffer(
    private val rangeStartSample: Long,
    private val rangeEndSample: Long?,
    chunkSamples: Int,
    private val onChunk: (TimedPcmChunk) -> Boolean,
) {
    init {
        require(rangeStartSample >= 0)
        require(rangeEndSample == null || rangeEndSample > rangeStartSample)
        require(chunkSamples > 0)
    }

    private val carry = FloatArray(chunkSamples)
    private var carrySize = 0
    private var carryStartSample = 0L
    private var acceptedEndSample: Long? = null

    var emittedEndSample: Long? = null
        private set
    var stoppedEarly = false
        private set
    var reachedRangeEnd = false
        private set
    val isActive: Boolean get() = !stoppedEarly && !reachedRangeEnd

    fun append(samples: FloatArray, startSample: Long): Boolean {
        if (!isActive) return false
        if (samples.isEmpty()) return true
        val bufferEndSample = startSample + samples.size
        val firstSample = maxOf(startSample, rangeStartSample, acceptedEndSample ?: Long.MIN_VALUE)
        val lastSample = minOf(bufferEndSample, rangeEndSample ?: Long.MAX_VALUE)

        if (firstSample < lastSample) {
            if (carrySize > 0 && firstSample != carryStartSample + carrySize && !flush()) return false
            var sourceOffset = (firstSample - startSample).toInt()
            var remaining = (lastSample - firstSample).toInt()
            while (remaining > 0) {
                if (carrySize == 0) carryStartSample = startSample + sourceOffset
                val count = minOf(carry.size - carrySize, remaining)
                System.arraycopy(samples, sourceOffset, carry, carrySize, count)
                carrySize += count
                sourceOffset += count
                remaining -= count
                acceptedEndSample = carryStartSample + carrySize
                if (carrySize == carry.size && !flush()) return false
            }
        }

        if (rangeEndSample != null && bufferEndSample >= rangeEndSample) {
            if (!flush()) return false
            reachedRangeEnd = true
        }
        return isActive
    }

    fun finishSegment(): Boolean = if (isActive) flush() else false

    private fun flush(): Boolean {
        if (carrySize == 0) return !stoppedEarly
        val chunk = TimedPcmChunk(carry.copyOf(carrySize), carryStartSample)
        carrySize = 0
        emittedEndSample = chunk.endSample
        if (!onChunk(chunk)) stoppedEarly = true
        return !stoppedEarly
    }
}

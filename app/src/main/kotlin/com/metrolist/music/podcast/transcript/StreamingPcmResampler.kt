/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

/** Retains interpolation phase and incomplete channel frames across decoder buffers. */
internal class StreamingPcmResampler(
    private val sourceSampleRate: Int,
    private val channelCount: Int,
    private val targetSampleRate: Int = 16_000,
) {
    init {
        require(sourceSampleRate > 0 && targetSampleRate > 0)
        require(channelCount > 0)
    }

    var inputFrameCount: Long = 0
        private set
    var outputSampleCount: Long = 0
        private set

    private var partialChannelCount = 0
    private var partialChannelSum = 0.0
    private var pendingStartFrame = 0L
    private var pendingMono = FloatArray(0)
    private var finished = false

    val hasInput: Boolean get() = inputFrameCount > 0 || partialChannelCount > 0

    fun process(pcm: ShortArray): FloatArray = append(pcm.size) { pcm[it] / 32768.0 }

    fun process(pcm: FloatArray): FloatArray = append(pcm.size) {
        require(pcm[it].isFinite()) { "PCM samples must be finite" }
        pcm[it].toDouble().coerceIn(-1.0, 1.0)
    }

    fun finish(): FloatArray {
        if (finished) return FloatArray(0)
        check(partialChannelCount == 0) { "Incomplete PCM channel frame" }
        finished = true
        return drain(endOfInput = true)
    }

    private inline fun append(sampleCount: Int, sampleAt: (Int) -> Double): FloatArray {
        check(!finished) { "Resampler has already finished" }
        if (sampleCount == 0) return FloatArray(0)
        val newFrames = (partialChannelCount + sampleCount) / channelCount
        val combined = pendingMono.copyOf(pendingMono.size + newFrames)
        var destination = pendingMono.size
        for (index in 0 until sampleCount) {
            partialChannelSum += sampleAt(index)
            partialChannelCount++
            if (partialChannelCount == channelCount) {
                combined[destination++] = (partialChannelSum / channelCount).toFloat()
                partialChannelCount = 0
                partialChannelSum = 0.0
            }
        }
        inputFrameCount += newFrames
        pendingMono = combined
        return drain(endOfInput = false)
    }

    private fun drain(endOfInput: Boolean): FloatArray {
        // Count against the entire decoded stream, never floor each individual
        // codec buffer. Integer positions also avoid floating-point phase drift.
        val targetCount = inputFrameCount * targetSampleRate / sourceSampleRate
        val remaining = targetCount - outputSampleCount
        check(remaining <= Int.MAX_VALUE) { "PCM buffer is too large" }
        val result = FloatArray(remaining.toInt())
        var written = 0
        while (outputSampleCount < targetCount) {
            val numerator = outputSampleCount * sourceSampleRate
            val sourceFrame = numerator / targetSampleRate
            val fraction = (numerator % targetSampleRate).toDouble() / targetSampleRate
            val left = (sourceFrame - pendingStartFrame).toInt()
            check(left in pendingMono.indices) { "Missing interpolation history" }
            if (fraction != 0.0 && left == pendingMono.lastIndex && !endOfInput) break
            val right = (left + 1).coerceAtMost(pendingMono.lastIndex)
            result[written++] = (pendingMono[left] + (pendingMono[right] - pendingMono[left]) * fraction).toFloat()
            outputSampleCount++
        }

        val nextSourceFrame = outputSampleCount * sourceSampleRate / targetSampleRate
        val consumed = (nextSourceFrame - pendingStartFrame).coerceIn(0L, pendingMono.size.toLong()).toInt()
        pendingMono = if (endOfInput) FloatArray(0) else pendingMono.copyOfRange(consumed, pendingMono.size)
        pendingStartFrame += consumed
        return if (written == result.size) result else result.copyOf(written)
    }
}

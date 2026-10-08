/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

internal fun stableAsrCues(
    streamOffsetMs: Long,
    text: String,
    startSeconds: Float,
    durationSeconds: Float,
    complete: Boolean,
    audioEndMs: Long = Long.MAX_VALUE,
): List<SubtitleCue> {
    if (!complete || text.isBlank() || !startSeconds.isFinite() || !durationSeconds.isFinite() || durationSeconds <= 0f) {
        return emptyList()
    }
    val startMs = streamOffsetMs + (startSeconds * 1_000).toLong().coerceAtLeast(0)
    val endMs = (startMs + (durationSeconds * 1_000).toLong().coerceAtLeast(1)).coerceAtMost(audioEndMs)
    if (endMs <= startMs) return emptyList()
    return PodcastAsrSupport.splitLongCue(SubtitleCue(startMs, endMs, text.trim()))
}

internal fun shouldRotateAsrStream(elapsedMs: Long, hasIncompleteLines: Boolean): Boolean =
    elapsedMs >= 360_000 || (elapsedMs >= 300_000 && !hasIncompleteLines)

/** Keeps stable cue indices across native streams whose line IDs restart from zero. */
internal class AsrTranscriptAccumulator(
    private val sampleRate: Int = 16_000,
    initialSample: Long = 0L,
    retainedCues: List<SubtitleCue> = emptyList(),
) {
    init {
        require(sampleRate > 0 && initialSample >= 0)
    }

    private data class Line(
        val text: String,
        val start: Float,
        val duration: Float,
        val complete: Boolean,
        val cues: List<SubtitleCue>,
    )
    private val completed = retainedCues.filter {
        it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= initialSample * 1_000 / sampleRate
    }.sortedBy { it.startMs }.toMutableList()
    private val current = linkedMapOf<Long, Line>()
    private var cachedSnapshot: List<SubtitleCue>? = null
    var streamStartSample = initialSample
        private set
    val hasIncompleteLines: Boolean get() = current.values.any { !it.complete }

    fun update(id: Long, text: String, start: Float, duration: Float, complete: Boolean) {
        val previous = current[id]
        if (previous != null && previous.text == text && previous.start == start &&
            previous.duration == duration && previous.complete == complete
        ) return
        val cues = stableAsrCues(streamStartSample * 1_000 / sampleRate, text, start, duration, complete)
        current[id] = Line(text, start, duration, complete, cues)
        // Changing hypotheses are not displayed. Reuse stable text and its split cues until
        // a final line changes instead of rebuilding the entire transcript every audio chunk.
        if (previous?.complete == true || complete) cachedSnapshot = null
    }

    fun snapshot(totalSamples: Long): List<SubtitleCue> {
        val cues = cachedSnapshot ?: (completed + current.values.sortedBy { it.start }.flatMap { it.cues })
            .also { cachedSnapshot = it }
        val audioEndMs = totalSamples * 1_000 / sampleRate
        if (cues.none { it.endMs > audioEndMs }) return cues
        return completed + current.values.sortedBy { it.start }.flatMap {
            stableAsrCues(streamStartSample * 1_000 / sampleRate, it.text, it.start, it.duration, it.complete, audioEndMs)
        }
    }

    fun finishStream(totalSamples: Long) {
        val cues = snapshot(totalSamples)
        completed.clear()
        completed.addAll(cues)
        current.clear()
        streamStartSample = totalSamples
        cachedSnapshot = cues
    }
}

/** Skips only decoded PCM; the decoder keeps its original sample clock and resampling phase. */
internal class AsrResumeCursor(resumePositionMs: Long, sampleRate: Int = 16_000) {
    init {
        require(sampleRate > 0 && resumePositionMs >= 0 && resumePositionMs <= Long.MAX_VALUE / sampleRate)
    }

    val resumeSample = resumePositionMs.coerceAtLeast(0) * sampleRate / 1_000
    var decodedSamples = 0L
        private set

    fun remaining(samples: FloatArray): FloatArray {
        val skip = (resumeSample - decodedSamples).coerceIn(0L, samples.size.toLong()).toInt()
        decodedSamples += samples.size
        return when (skip) {
            0 -> samples
            samples.size -> EMPTY
            else -> samples.copyOfRange(skip, samples.size)
        }
    }

    fun verifyEndOfInput() {
        check(decodedSamples >= resumeSample) { "Audio ended before the saved recognition checkpoint" }
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}

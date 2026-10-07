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
internal class AsrTranscriptAccumulator(private val sampleRate: Int = 16_000) {
    private data class Line(val text: String, val start: Float, val duration: Float, val complete: Boolean)
    private val completed = mutableListOf<SubtitleCue>()
    private val current = linkedMapOf<Long, Line>()
    var streamStartSample = 0L
        private set
    val hasIncompleteLines: Boolean get() = current.values.any { !it.complete }

    fun update(id: Long, text: String, start: Float, duration: Float, complete: Boolean) {
        current[id] = Line(text, start, duration, complete)
    }

    fun snapshot(totalSamples: Long): List<SubtitleCue> = completed + currentCues(totalSamples)

    fun finishStream(totalSamples: Long) {
        completed += currentCues(totalSamples)
        current.clear()
        streamStartSample = totalSamples
    }

    private fun currentCues(totalSamples: Long): List<SubtitleCue> = current.values.sortedBy { it.start }.flatMap {
        stableAsrCues(streamStartSample * 1_000 / sampleRate, it.text, it.start, it.duration, it.complete, totalSamples * 1_000 / sampleRate)
    }
}

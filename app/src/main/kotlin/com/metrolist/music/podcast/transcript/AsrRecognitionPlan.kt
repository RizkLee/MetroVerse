/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

/** Media-time coverage, including decoded silence, rather than the extent of subtitle text. */
@Serializable
data class AsrAudioRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0 && endMs > startMs)
    }

    operator fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

internal data class AsrWorkRange(val startMs: Long, val endMs: Long?)

internal data class AsrSeekTarget(val previousPositionMs: Long, val positionMs: Long)

/** Coalesces a scrub or repeated skip presses into one final, settled seek. */
internal class AsrSeekRequests(private val nowMs: () -> Long) {
    private data class Pending(val target: AsrSeekTarget, val updatedAtMs: Long)
    private val pending = AtomicReference<Pending?>(null)

    fun offer(previousPositionMs: Long, positionMs: Long) {
        val now = nowMs()
        pending.updateAndGet { old ->
            val origin = old?.takeIf { now - it.updatedAtMs < SETTLE_MS }?.target?.previousPositionMs
                ?: previousPositionMs
            Pending(AsrSeekTarget(origin.coerceAtLeast(0), positionMs.coerceAtLeast(0)), now)
        }
    }

    fun poll(): AsrSeekTarget? {
        while (true) {
            val value = pending.get() ?: return null
            if (nowMs() - value.updatedAtMs < SETTLE_MS) return null
            if (pending.compareAndSet(value, null)) return value.target
        }
    }

    fun clear() { pending.set(null) }

    private companion object { const val SETTLE_MS = 2_000L }
}

enum class AsrRecognitionPhase { PREPARING, RESUMING, RECOGNIZING }

data class AsrRecognitionSnapshot(
    val cues: List<SubtitleCue>,
    val processedRanges: List<AsrAudioRange>,
    val resumePositionMs: Long,
    val progressPercent: Int,
    val audioDurationMs: Long?,
    val phase: AsrRecognitionPhase = AsrRecognitionPhase.RECOGNIZING,
)

internal class AsrRecordingChangedException : IllegalStateException("Audio ends before saved recognition coverage")

internal fun verifyAsrMediaEnd(endMs: Long, savedRanges: List<AsrAudioRange>, savedCues: List<SubtitleCue>) {
    val savedEnd = maxOf(savedRanges.maxOfOrNull { it.endMs } ?: 0, savedCues.maxOfOrNull { it.endMs } ?: 0)
    if (savedEnd - endMs > 1_000L) throw AsrRecordingChangedException()
}

internal fun finalizedAsrBoundary(startMs: Long, decodedEndMs: Long, stableCues: List<SubtitleCue>): Long =
    minOf(decodedEndMs, maxOf(startMs, stableCues.maxOfOrNull { it.endMs } ?: startMs))

internal fun mergeAsrRanges(ranges: List<AsrAudioRange>): List<AsrAudioRange> {
    val merged = mutableListOf<AsrAudioRange>()
    for (range in ranges.sortedBy { it.startMs }) {
        val previous = merged.lastOrNull()
        if (previous != null && range.startMs <= previous.endMs) {
            merged[merged.lastIndex] = AsrAudioRange(previous.startMs, maxOf(previous.endMs, range.endMs))
        } else {
            merged += range
        }
    }
    return merged
}

/** Only final user seeks can reprioritize work; small forward skips keep the current stream. */
internal fun shouldPrioritizeAsrSeek(
    target: AsrSeekTarget,
    activeStartMs: Long,
    activeEndMs: Long,
    processedRanges: List<AsrAudioRange>,
): Boolean {
    if (target.positionMs < 0 || processedRanges.any { target.positionMs in it }) return false
    if (target.positionMs in activeStartMs until activeEndMs) return false
    val forwardBy = target.positionMs - target.previousPositionMs
    return if (forwardBy > 0) {
        forwardBy > 120_000
    } else {
        target.positionMs < activeStartMs
    }
}

/** A single recognizer visits missing ranges; deferred work contains positions, never models. */
internal class AsrRecognitionPlan(
    ranges: List<AsrAudioRange> = emptyList(),
    knownEndMs: Long? = null,
) {
    var processedRanges: List<AsrAudioRange> = mergeAsrRanges(ranges)
        private set
    var audioEndMs: Long? = knownEndMs?.takeIf { it > 0 }
        private set
    private val deferredStarts = ArrayDeque<Long>()

    fun record(startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        processedRanges = mergeAsrRanges(processedRanges + AsrAudioRange(startMs.coerceAtLeast(0), endMs))
    }

    fun reachedEnd(endMs: Long) {
        require(endMs >= 0)
        audioEndMs = endMs
        processedRanges = processedRanges.mapNotNull { range ->
            val end = minOf(range.endMs, endMs)
            if (end > range.startMs) AsrAudioRange(range.startMs, end) else null
        }
    }

    fun defer(positionMs: Long) {
        if (positionMs >= 0 && positionMs !in deferredStarts) {
            deferredStarts.addFirst(positionMs)
            // Rapid navigation must not accumulate an unbounded history of abandoned targets.
            while (deferredStarts.size > 16) deferredStarts.removeLast()
        }
    }

    fun next(preferredPositionMs: Long? = null): AsrWorkRange? {
        preferredPositionMs?.let { missingAtOrAfter(it)?.let { range -> return range } }
        while (deferredStarts.isNotEmpty()) {
            missingAtOrAfter(deferredStarts.removeFirst())?.let { return it }
        }
        return missingAtOrAfter(0)
    }

    fun progress(durationHintMs: Long, activeRange: AsrAudioRange? = null): Int {
        val ranges = activeRange?.let { mergeAsrRanges(processedRanges + it) } ?: processedRanges
        val duration = audioEndMs ?: maxOf(durationHintMs, ranges.lastOrNull()?.endMs ?: 0)
        if (duration <= 0) return 0
        val covered = ranges.sumOf { (minOf(it.endMs, duration) - it.startMs).coerceAtLeast(0) }
        return (covered.toDouble() * 100 / duration).toInt().coerceIn(0, 99)
    }

    private fun missingAtOrAfter(positionMs: Long): AsrWorkRange? {
        var start = positionMs.coerceAtLeast(0)
        for (range in processedRanges) {
            if (start < range.startMs) return boundedRange(start, range.startMs)
            if (start < range.endMs) start = range.endMs
        }
        return boundedRange(start, audioEndMs)
    }

    private fun boundedRange(startMs: Long, proposedEndMs: Long?): AsrWorkRange? {
        val end = audioEndMs?.let { minOf(it, proposedEndMs ?: it) } ?: proposedEndMs
        return if (end != null && startMs >= end) null else AsrWorkRange(startMs, end)
    }
}

/** Replaces only one media interval; earlier/later saved subtitles and their translations survive. */
internal fun replaceAsrRangeCues(
    previous: List<SubtitleCue>,
    replacement: List<SubtitleCue>,
    startMs: Long,
    endMs: Long,
): List<SubtitleCue> {
    if (endMs <= startMs) return previous
    val retained = previous.filter { it.endMs <= startMs || it.startMs >= endMs }
    val fresh = replacement.mapNotNull { cue ->
        val start = maxOf(cue.startMs, startMs)
        val end = minOf(cue.endMs, endMs)
        if (end > start && cue.text.isNotBlank()) cue.copy(startMs = start, endMs = end) else null
    }
    return (retained + fresh).distinct().sortedBy { it.startMs }
}

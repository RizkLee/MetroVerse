/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

/** Pure helpers shared by the on-device podcast transcription pipeline. JVM-testable. */
object PodcastAsrSupport {

    /** Maximum duration of a single subtitle cue produced from speech recognition. */
    const val MAX_ASR_CUE_DURATION_MS = 12_000L

    /**
     * Splits cues longer than [PodcastAsrSupport.MAX_ASR_CUE_DURATION_MS] into roughly even
     * pieces, distributing time proportionally to character length.
     */
    fun splitLongCue(cue: SubtitleCue): List<SubtitleCue> {
        val duration = cue.endMs - cue.startMs
        if (duration <= MAX_ASR_CUE_DURATION_MS) return listOf(cue)

        val pieces = ((duration + MAX_ASR_CUE_DURATION_MS - 1) / MAX_ASR_CUE_DURATION_MS).toInt()
        val words = cue.text.split(' ').filter { it.isNotBlank() }
        if (pieces <= 1 || words.size < pieces) return listOf(cue)

        val perPiece = words.size / pieces.toDouble()
        val boundaries = (1 until pieces).map { index -> (index * perPiece).toInt().coerceIn(1, words.size - 1) }
        val chunks = buildList {
            var start = 0
            boundaries.forEach { boundary ->
                add(words.subList(start, boundary).joinToString(" "))
                start = boundary
            }
            add(words.subList(start, words.size).joinToString(" "))
        }

        val totalChars = chunks.sumOf { it.length }.coerceAtLeast(1)
        var cursorMs = cue.startMs
        return chunks.mapIndexedNotNull { index, text ->
            val nextCursor = if (index == chunks.lastIndex) {
                cue.endMs
            } else {
                cursorMs + (cue.endMs - cue.startMs) * text.length / totalChars
            }
            val start = cursorMs
            cursorMs = nextCursor
            if (text.isBlank()) null else SubtitleCue(start, maxOf(nextCursor, start), text)
        }
    }

    /** Latest cue that has started, preserving its highlight during gaps until the next cue. */
    fun cueIndexAt(cues: List<SubtitleCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        var low = 0
        var high = cues.lastIndex
        var best = -1
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (cues[middle].startMs <= positionMs) {
                best = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }
}

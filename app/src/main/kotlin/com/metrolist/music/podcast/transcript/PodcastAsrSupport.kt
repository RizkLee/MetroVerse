/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import java.text.BreakIterator
import java.util.Locale

/** Pure helpers shared by the on-device podcast transcription pipeline. JVM-testable. */
object PodcastAsrSupport {

    /** Extracted YouTube URLs are not stable sources for RSS recognition checkpoints. */
    fun supportsOnDeviceTranscription(isEpisode: Boolean, mediaUrl: String?): Boolean =
        isEpisode && !mediaUrl.isNullOrBlank()

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
        if (pieces <= 1) return listOf(cue)
        // Mandarin/Japanese output has no word spaces; keep grapheme clusters intact when splitting it.
        val splitByCharacter = words.size == 1 && cue.text.codePoints().anyMatch {
            Character.UnicodeScript.of(it) in setOf(
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            )
        }
        val units = if (splitByCharacter) {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(cue.text)
            buildList {
                var start = iterator.first()
                var end = iterator.next()
                while (end != BreakIterator.DONE) {
                    add(cue.text.substring(start, end))
                    start = end
                    end = iterator.next()
                }
            }
        } else words
        if (units.size < pieces) return listOf(cue)

        val perPiece = units.size / pieces.toDouble()
        val boundaries = (1 until pieces).map { index -> (index * perPiece).toInt().coerceIn(1, units.size - 1) }
        val separator = if (splitByCharacter) "" else " "
        val chunks = buildList {
            var start = 0
            boundaries.forEach { boundary ->
                add(units.subList(start, boundary).joinToString(separator))
                start = boundary
            }
            add(units.subList(start, units.size).joinToString(separator))
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

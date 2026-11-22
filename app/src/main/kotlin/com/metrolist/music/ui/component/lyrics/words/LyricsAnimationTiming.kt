/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component.lyrics.words

import com.metrolist.music.lyrics.WordTimestamp

internal const val MIN_WORD_ANIMATION_DURATION_MS = 100L

internal fun lyricsWordAnimationDurationMs(durationMs: Long): Long = durationMs.coerceAtLeast(MIN_WORD_ANIMATION_DURATION_MS)

internal fun lyricsWordAnimationRangeMs(words: List<WordTimestamp>): LongRange? {
    if (words.isEmpty()) return null
    val startMs = words.minOf { (it.startTime * 1000).toLong() }
    val endMs =
        words.maxOf {
            val wordStartMs = (it.startTime * 1000).toLong()
            wordStartMs + lyricsWordAnimationDurationMs((it.endTime * 1000).toLong() - wordStartMs)
        }
    return startMs..endMs
}

internal fun lyricsWordAnimationProgress(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
): Float {
    val durationMs = lyricsWordAnimationDurationMs(endMs - startMs)
    return ((positionMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
}

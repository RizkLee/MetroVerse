/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

internal object SubtitleTimingOffset {
    const val LIMIT_MS = 10_000
    const val STEP_MS = 50

    fun clamp(offsetMs: Int): Int = offsetMs.coerceIn(-LIMIT_MS, LIMIT_MS)

    // Match lyrics: a positive offset advances the subtitle clock, not the audio clock.
    fun subtitlePosition(playbackPositionMs: Long, offsetMs: Int): Long =
        addOffset(playbackPositionMs.coerceAtLeast(0L), clamp(offsetMs).toLong())

    fun playbackPosition(cueStartMs: Long, offsetMs: Int, durationMs: Long = 0L): Long =
        addOffset(cueStartMs.coerceAtLeast(0L), -clamp(offsetMs).toLong())
            .coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)

    private fun addOffset(positionMs: Long, offsetMs: Long): Long =
        if (offsetMs > 0L && positionMs > Long.MAX_VALUE - offsetMs) Long.MAX_VALUE
        else positionMs + offsetMs
}

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
     * Linear-interpolation resample of interleaved 16-bit PCM, downmixed to mono.
     * Returns a new float array in [-1, 1] at [targetSampleRate].
     */
    fun resampleToMono16k(
        pcm: ShortArray,
        channelCount: Int,
        sourceSampleRate: Int,
        targetSampleRate: Int = 16_000,
    ): FloatArray {
        require(channelCount >= 1) { "channelCount must be positive" }
        if (pcm.isEmpty() || sourceSampleRate <= 0) return FloatArray(0)
        if (channelCount == 1 && sourceSampleRate == targetSampleRate) {
            return FloatArray(pcm.size) { pcm[it] / 32768f }
        }

        val sourceFrames = pcm.size / channelCount
        val targetFrames = ((sourceFrames.toLong() * targetSampleRate) / sourceSampleRate).toInt()
        if (targetFrames <= 0) return FloatArray(0)

        val result = FloatArray(targetFrames)
        val step = sourceSampleRate.toFloat() / targetSampleRate
        for (frame in 0 until targetFrames) {
            val sourcePosition = frame * step
            val left = sourcePosition.toInt().coerceAtMost(sourceFrames - 1)
            val right = (left + 1).coerceAtMost(sourceFrames - 1)
            val fraction = sourcePosition - left
            var sample = 0f
            for (channel in 0 until channelCount) {
                val a = pcm[left * channelCount + channel]
                val b = pcm[right * channelCount + channel]
                sample += ((a + (b - a) * fraction)) / 32768f
            }
            result[frame] = sample / channelCount
        }
        return result
    }

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

    /** Normalizes SenseVoice language tags (`<|zh|>`, `zh`, ...) into a bare ISO-ish code. */
    fun normalizeLanguageTag(raw: String?): String? {
        val cleaned = raw
            ?.let { value ->
                value
                    .replace("<|", "")
                    .replace("|>", "")
                    .trim()
                    .lowercase()
            }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return when {
            cleaned.startsWith("zh") -> "zh"
            cleaned.startsWith("en") -> "en"
            cleaned.startsWith("ja") -> "ja"
            cleaned.startsWith("ko") -> "ko"
            cleaned.startsWith("yue") -> "yue"
            else -> cleaned.take(2)
        }
    }

    /** Index of the cue covering [positionMs], or -1 when playback is between cues. */
    fun cueIndexAt(cues: List<SubtitleCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        var low = 0
        var high = cues.lastIndex
        var best = -1
        while (low <= high) {
            val middle = (low + high) / 2
            val cue = cues[middle]
            when {
                positionMs < cue.startMs -> high = middle - 1
                positionMs > cue.endMs -> {
                    // Keep the last cue that started before the position as a fallback for
                    // gaps between cues.
                    best = middle
                    low = middle + 1
                }
                else -> return middle
            }
        }
        return best
    }
}

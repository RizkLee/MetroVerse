/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

internal data class TranslationCue(val cue: SubtitleCue, val key: String)

internal sealed interface SubtitleTranslationPlan {
    data class Batch(val indices: List<Int>) : SubtitleTranslationPlan
    data class Wait(val untilMs: Long) : SubtitleTranslationPlan
    data object Idle : SubtitleTranslationPlan
}

/** Nearby missing lines have a short coalescing window; distant window edges wait for a batch. */
internal fun planSubtitleTranslation(
    cues: List<TranslationCue>,
    translatedKeys: Set<String>,
    centerIndex: Int,
    nowMs: Long,
    pendingSinceMs: Long,
    lastRequestMs: Long?,
    isStreaming: Boolean = false,
    prioritizeSeek: Boolean = false,
    pendingIndices: Set<Int> = emptySet(),
): SubtitleTranslationPlan {
    if (cues.isEmpty()) return SubtitleTranslationPlan.Idle
    val center = centerIndex.coerceIn(0, cues.lastIndex)
    val first = (center - 1).coerceAtLeast(0)
    val last = (center + TRANSLATION_LOOKAHEAD).coerceAtMost(cues.lastIndex)
    val missing = ((first..last).toList() + pendingIndices.filter { it in cues.indices }).distinct()
        .filter { cues[it].cue.text.isNotBlank() && cues[it].key !in translatedKeys }
    if (missing.isEmpty()) return SubtitleTranslationPlan.Idle

    val urgentEnd = (center + TRANSLATION_URGENT_AHEAD).coerceAtMost(last)
    val urgent = missing.any { it <= urgentEnd }
    val minimumRequestTime = lastRequestMs?.let { it + TRANSLATION_MIN_REQUEST_INTERVAL_MS } ?: nowMs
    val streamingTail = isStreaming && !prioritizeSeek && lastRequestMs != null &&
        center >= cues.lastIndex - TRANSLATION_URGENT_AHEAD && missing.size < TRANSLATION_STREAMING_MIN_LINES
    val coalescedAt = pendingSinceMs + when {
        streamingTail -> TRANSLATION_STREAMING_WAIT_MS
        urgent -> TRANSLATION_URGENT_WAIT_MS
        else -> TRANSLATION_PREFETCH_WAIT_MS
    }
    val enoughToPrefetch = missing.size >= TRANSLATION_PREFETCH_LINES ||
        missing.sumOf { cues[it].cue.text.length } >= TRANSLATION_PREFETCH_CHARS
    val readyAt = maxOf(minimumRequestTime, if (!urgent && enoughToPrefetch) nowMs else coalescedAt)
    if (nowMs < readyAt) return SubtitleTranslationPlan.Wait(readyAt)

    val selected = mutableListOf<Int>()
    var characters = 0
    val priority = missing.sortedWith(compareBy<Int> {
        when {
            it == center -> 0
            it in center..urgentEnd -> 1
            it < center -> 2
            else -> 3
        }
    }.thenBy { it })
    for (index in priority) {
        val nextCharacters = cues[index].cue.text.length
        if (selected.isNotEmpty() &&
            (selected.size >= TRANSLATION_MAX_BATCH_LINES || characters + nextCharacters > TRANSLATION_MAX_BATCH_CHARS)
        ) break
        selected += index
        characters += nextCharacters
    }
    return SubtitleTranslationPlan.Batch(selected.sorted())
}

internal class SubtitleTranslationCooldown {
    private var failures = 0
    var retryAtMs: Long = 0
        private set
    var requiresExplicitRetry: Boolean = false
        private set
    var lastRequestMs: Long? = null
        private set

    fun started(nowMs: Long) { lastRequestMs = nowMs }

    fun failed(nowMs: Long, statusCode: Int? = null, retryAfterMs: Long? = null) {
        failures = (failures + 1).coerceAtMost(3)
        requiresExplicitRetry = failures >= 3 || (statusCode != null && statusCode in 400..499 && statusCode != 408 && statusCode != 429)
        val baseDelay = if (statusCode == 429) 30_000L else 10_000L
        val delay = maxOf((baseDelay shl (failures - 1)).coerceAtMost(300_000L), retryAfterMs ?: 0L)
        retryAtMs = if (Long.MAX_VALUE - nowMs < delay) Long.MAX_VALUE else nowMs + delay
    }

    fun succeeded() {
        failures = 0
        retryAtMs = 0
        requiresExplicitRetry = false
    }
}

private const val TRANSLATION_LOOKAHEAD = 48
private const val TRANSLATION_URGENT_AHEAD = 3
private const val TRANSLATION_PREFETCH_LINES = 12
private const val TRANSLATION_PREFETCH_CHARS = 1_800
private const val TRANSLATION_MAX_BATCH_LINES = 24
private const val TRANSLATION_MAX_BATCH_CHARS = 6_000
private const val TRANSLATION_URGENT_WAIT_MS = 750L
private const val TRANSLATION_PREFETCH_WAIT_MS = 30_000L
private const val TRANSLATION_MIN_REQUEST_INTERVAL_MS = 2_000L
private const val TRANSLATION_STREAMING_MIN_LINES = 4
private const val TRANSLATION_STREAMING_WAIT_MS = 12_000L

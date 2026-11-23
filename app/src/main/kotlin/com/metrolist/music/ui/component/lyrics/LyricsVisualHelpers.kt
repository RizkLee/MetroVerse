/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component.lyrics

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.metrolist.music.constants.LYRICS_TEXT_SIZE_RANGE
import com.metrolist.music.ui.component.LyricsListItem

private const val MAX_PROGRESSIVE_BLUR_DP = 7.5f
private const val PROGRESSIVE_BLUR_STEP_DP = 1.5f

internal fun zoomedLyricsTextSize(
    size: Float,
    zoom: Float,
): Float = if (zoom.isFinite() && zoom > 0f) (size * zoom).coerceIn(LYRICS_TEXT_SIZE_RANGE) else size

/**
 * Computes per-row progressive blur radius in dp.
 *
 * Unlike viewport-level blur shaders, per-row blur lets the bottom [LyricsListItem.Provider]
 * attribution row opt out unconditionally (`isAttributionItem = true` always returns `0f`),
 * keeping provider and songwriter credits crisp at the end of every song.
 */
internal fun computeProgressiveLyricsBlurDp(
    distance: Int,
    enabled: Boolean,
    isSynced: Boolean,
    isAutoScrollEnabled: Boolean,
    isManualScrolling: Boolean,
    isSelectionMode: Boolean,
    isAttributionItem: Boolean = false,
): Float {
    if (isAttributionItem ||
        !enabled ||
        !isSynced ||
        !isAutoScrollEnabled ||
        isManualScrolling ||
        isSelectionMode ||
        distance <= 0
    ) {
        return 0f
    }
    return (distance * PROGRESSIVE_BLUR_STEP_DP).coerceAtMost(MAX_PROGRESSIVE_BLUR_DP)
}

internal fun computeAppleDistanceScale(
    distance: Int,
    isActive: Boolean,
    isSynced: Boolean,
    isAutoScrollEnabled: Boolean,
    isSelectionMode: Boolean,
): Float {
    if (isActive || !isAutoScrollEnabled || isSelectionMode || !isSynced) return 1.0f
    return when (distance) {
        0, 1 -> 0.97f
        2, 3 -> 0.93f
        4 -> 0.90f
        else -> 0.85f
    }
}

internal fun appendLyricsAttributionItem(
    items: List<LyricsListItem>,
    provider: String?,
    songwriters: List<String>,
): List<LyricsListItem> {
    val validProvider =
        provider?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) && !it.equals("Manual", ignoreCase = true)
        }
    val validSongwriters = songwriters.map(String::trim).filter(String::isNotEmpty).distinct()
    if (validProvider == null && validSongwriters.isEmpty()) return items
    return items + LyricsListItem.Provider(provider = validProvider, songwriters = validSongwriters)
}

internal fun formatWrittenByAnnotated(
    template: String,
    writers: List<String>,
): AnnotatedString {
    val cleanWriters = writers.map(String::trim).filter(String::isNotEmpty).distinct()
    val writersText = cleanWriters.joinToString(", ")
    val colonIndex = template.indexOfAny(charArrayOf(':', '：'))
    val splitIndex =
        when {
            colonIndex >= 0 && colonIndex + 1 <= template.length -> colonIndex + 1
            else -> template.indexOf(writersText).takeIf { it > 0 } ?: -1
        }
    if (splitIndex in 1 until template.length) {
        return buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(template.substring(0, splitIndex))
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
                append(template.substring(splitIndex))
            }
        }
    }
    return AnnotatedString(template)
}

internal fun nextLyricsDeadlineIndex(
    deadlines: List<Long>,
    positionMs: Long,
): Int {
    val index = deadlines.binarySearch(positionMs)
    return if (index >= 0) index + 1 else -index - 1
}

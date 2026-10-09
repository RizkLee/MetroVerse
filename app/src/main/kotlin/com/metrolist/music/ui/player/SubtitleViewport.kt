/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

internal fun subtitleAnchorTopPx(
    viewportHeightPx: Float,
    activeLineHeightPx: Float,
    safeTopPx: Float,
    headerHeightPx: Float = 0f,
    headerGapPx: Float = 0f,
): Float {
    val height = viewportHeightPx.coerceAtLeast(0f)
    val headerSpace = if (headerHeightPx > 0f) headerHeightPx + headerGapPx.coerceAtLeast(0f) else 0f
    val minimum = (safeTopPx.coerceAtLeast(0f) + headerSpace).coerceAtMost(height)
    return (height * 0.34f - activeLineHeightPx.coerceAtLeast(0f) * 2f)
        .coerceIn(minimum, height)
}

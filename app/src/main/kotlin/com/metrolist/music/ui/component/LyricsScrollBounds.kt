/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

/**
 * Scroll clamp for the lyrics list.
 *
 * Item i sits at y = anchorY + positions[i] + offset, where positions are relative to the
 * active line (positions[active] == 0). `contentAbove` is the stacked height of every item
 * above the active line (-positions[0]); `contentBelow` is positions[last] (active through
 * the item before the last).
 *
 * - maxOffset (drag toward the beginning) stops with the first item's top at [topPadding].
 * - minOffset (drag toward the end) stops with the last item's bottom at viewport-[bottomPadding].
 */
internal data class LyricsScrollBounds(val minOffset: Float, val maxOffset: Float) {
    val clampMin: Float get() = minOf(minOffset, maxOffset)
    val clampMax: Float get() = maxOf(minOffset, maxOffset)
}

internal fun computeLyricsScrollBounds(
    contentAbove: Float,
    contentBelow: Float,
    lastItemHeight: Float,
    viewportHeight: Float,
    anchorY: Float,
    topPadding: Float,
    bottomPadding: Float,
): LyricsScrollBounds {
    val maxOffset = topPadding - anchorY + contentAbove
    val minOffset = viewportHeight - bottomPadding - lastItemHeight - anchorY - contentBelow
    return LyricsScrollBounds(minOffset = minOffset, maxOffset = maxOffset)
}

/**
 * Offset auto-scroll should rest at for the current active line.
 *
 * Mid-song this is 0 (active line on the anchor). Near the end it shifts so the last line
 * sits on the bottom padding instead of leaving blank space below. Near the start it shifts
 * so the first line sits on the top padding.
 */
internal fun computeAutoRestOffset(
    contentAbove: Float,
    contentBelow: Float,
    lastItemHeight: Float,
    viewportHeight: Float,
    anchorY: Float,
    topPadding: Float,
    bottomPadding: Float,
): Float {
    val bounds = computeLyricsScrollBounds(
        contentAbove = contentAbove,
        contentBelow = contentBelow,
        lastItemHeight = lastItemHeight,
        viewportHeight = viewportHeight,
        anchorY = anchorY,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
    )
    val firstY0 = anchorY - contentAbove
    val lastBottom0 = anchorY + contentBelow + lastItemHeight
    val bottomLimit = viewportHeight - bottomPadding
    val rest = when {
        // Not enough content below the anchor: pin the last line to the bottom padding.
        lastBottom0 < bottomLimit -> bottomLimit - lastBottom0
        // Not enough content above the anchor: pin the first line to the top padding.
        firstY0 > topPadding -> topPadding - firstY0
        else -> 0f
    }
    return rest.coerceIn(bounds.clampMin, bounds.clampMax)
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

/** A gap belongs to the item after it, regardless of which line currently owns the anchor. */
internal fun computeLyricsItemPositions(
    itemHeights: List<Float>,
    gapsBeforeItems: List<Float>,
    anchorIndex: Int,
): Map<Int, Float> {
    require(itemHeights.size == gapsBeforeItems.size)
    if (itemHeights.isEmpty()) return emptyMap()

    val starts = FloatArray(itemHeights.size)
    for (index in 1 until starts.size) {
        starts[index] = starts[index - 1] + itemHeights[index - 1] + gapsBeforeItems[index]
    }
    val anchor = starts[anchorIndex.coerceIn(starts.indices)]
    return starts.mapIndexed { index, start -> index to start - anchor }.toMap()
}

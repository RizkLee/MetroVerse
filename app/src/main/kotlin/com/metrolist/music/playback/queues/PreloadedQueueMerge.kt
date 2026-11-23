/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem

internal data class PreloadedQueueMergeResult(
    val beforeItems: List<MediaItem>,
    val afterItems: List<MediaItem>,
)

/**
 * Merges an asynchronously fetched [initialStatus] around an already-preloaded track without
 * replacing the user's selected item if watch-next omits or remaps the seed track ID.
 */
internal fun mergePreloadedQueueItems(
    preloadId: String,
    initialStatus: Queue.Status,
): PreloadedQueueMergeResult {
    val items = initialStatus.items
    if (items.isEmpty()) {
        return PreloadedQueueMergeResult(emptyList(), emptyList())
    }
    val preferredIndex = initialStatus.mediaItemIndex.coerceIn(0, items.lastIndex)
    val matchIndex =
        if (items[preferredIndex].mediaId == preloadId) {
            preferredIndex
        } else {
            items.indexOfFirst { it.mediaId == preloadId }
        }
    return if (matchIndex >= 0) {
        PreloadedQueueMergeResult(
            beforeItems = items.subList(0, matchIndex),
            afterItems = items.subList(matchIndex + 1, items.size),
        )
    } else {
        PreloadedQueueMergeResult(
            beforeItems = emptyList(),
            afterItems = items.filterNot { it.mediaId == preloadId },
        )
    }
}

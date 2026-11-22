/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?
    val continuationBelongsToCurrentCollection: Boolean
        get() = false

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        fun filter(predicate: (MediaItem) -> Boolean): Status {
            if (items.isEmpty()) {
                return copy(items = emptyList(), mediaItemIndex = 0, position = 0L)
            }
            val targetIndex = mediaItemIndex.coerceIn(0, items.lastIndex)
            val kept = ArrayList<MediaItem>(items.size)
            var keptBeforeTarget = 0
            var targetKept = false
            items.forEachIndexed { index, item ->
                if (predicate(item)) {
                    kept.add(item)
                    if (index < targetIndex) {
                        keptBeforeTarget++
                    } else if (index == targetIndex) {
                        targetKept = true
                    }
                }
            }
            if (kept.isEmpty()) {
                return copy(items = emptyList(), mediaItemIndex = 0, position = 0L)
            }
            val remappedIndex =
                if (targetKept) keptBeforeTarget else keptBeforeTarget.coerceIn(0, kept.lastIndex)
            val remappedPosition = if (targetKept) position else 0L
            return copy(items = kept, mediaItemIndex = remappedIndex, position = remappedPosition)
        }

        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) {
                filter { it.metadata?.explicit != true }
            } else {
                this
            }

        fun filterVideoSongs(disableVideos: Boolean = false) =
            if (disableVideos) {
                filter { it.metadata?.isVideoSong != true }
            } else {
                this
            }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it.metadata?.isVideoSong == true }
    } else {
        this
    }

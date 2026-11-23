package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PreloadedQueueMergeTest {
    private fun item(id: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .build()

    @Test
    fun `splits around exact preferred index when seed matches`() {
        val status =
            Queue.Status(
                title = "Radio",
                items = listOf(item("a"), item("seed"), item("b"), item("c")),
                mediaItemIndex = 1,
            )

        val result = mergePreloadedQueueItems("seed", status)
        assertEquals(listOf("a"), result.beforeItems.map { it.mediaId })
        assertEquals(listOf("b", "c"), result.afterItems.map { it.mediaId })
    }

    @Test
    fun `finds seed when preferred index points elsewhere`() {
        val status =
            Queue.Status(
                title = "Radio",
                items = listOf(item("a"), item("b"), item("seed"), item("c")),
                mediaItemIndex = 0,
            )

        val result = mergePreloadedQueueItems("seed", status)
        assertEquals(listOf("a", "b"), result.beforeItems.map { it.mediaId })
        assertEquals(listOf("c"), result.afterItems.map { it.mediaId })
    }

    @Test
    fun `keeps all returned items after preloaded track when seed missing`() {
        val status =
            Queue.Status(
                title = "Radio",
                items = listOf(item("next1"), item("next2")),
                mediaItemIndex = 0,
            )

        val result = mergePreloadedQueueItems("seed", status)
        assertEquals(emptyList<String>(), result.beforeItems.map { it.mediaId })
        assertEquals(listOf("next1", "next2"), result.afterItems.map { it.mediaId })
    }
}

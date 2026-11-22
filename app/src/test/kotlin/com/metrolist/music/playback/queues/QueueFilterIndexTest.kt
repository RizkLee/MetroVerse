/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import android.app.Application
import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.ContentFilterSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class QueueFilterIndexTest {
    @Test
    fun `filtering items before current index shifts index and preserves position`() {
        val status = Queue.Status(
            title = "Queue",
            items = listOf(
                item("bad-0", explicit = true),
                item("good-1", explicit = false),
                item("bad-2", explicit = true),
                item("current-3", explicit = false),
                item("good-4", explicit = false),
            ),
            mediaItemIndex = 3,
            position = 42_500L,
        )

        val filtered = ContentFilterSnapshot(hideExplicit = true).filterQueueStatus(status)
        assertEquals(listOf("good-1", "current-3", "good-4"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex)
        assertEquals(42_500L, filtered.position)
    }

    @Test
    fun `filtering active index moves to next valid item and resets position while keeping open rss`() {
        val status = Queue.Status(
            title = "Mixed",
            items = listOf(
                item("good-0", explicit = false),
                item("filtered-current", explicit = true, duration = 200),
                item("rss_episode_1", explicit = true, duration = 15, isEpisode = true, mediaUrl = "https://cdn.example.com/1.mp3"),
            ),
            mediaItemIndex = 1,
            position = 19_000L,
        )

        val strictMusicFilter = ContentFilterSnapshot(
            hideExplicit = true,
            hideShortSongs = true,
            minimumSongDurationSeconds = 60,
            hidePodcasts = true,
        )
        val filtered = strictMusicFilter.filterQueueStatus(status)
        assertEquals(listOf("good-0", "rss_episode_1"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex)
        assertEquals(0L, filtered.position)
    }

    @Test
    fun `filtering out all items returns empty queue at index zero`() {
        val status = Queue.Status(
            title = "EmptyAfterFilter",
            items = listOf(item("ex1", explicit = true)),
            mediaItemIndex = 0,
            position = 5_000L,
        )

        val filtered = status.filterExplicit(enabled = true)
        assertTrue(filtered.items.isEmpty())
        assertEquals(0, filtered.mediaItemIndex)
        assertEquals(0L, filtered.position)
    }

    private fun item(
        id: String,
        explicit: Boolean = false,
        duration: Int = 200,
        isEpisode: Boolean = false,
        mediaUrl: String? = null,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(mediaUrl ?: "https://music.youtube.com/watch?v=$id")
            .setTag(
                MediaMetadata(
                    id = id,
                    title = id,
                    artists = emptyList(),
                    duration = duration,
                    explicit = explicit,
                    isEpisode = isEpisode,
                    mediaUrl = mediaUrl,
                ),
            )
            .build()
}

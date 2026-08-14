package com.metrolist.music.podcast

import com.metrolist.music.models.MediaMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PodcastParsingTest {
    @Test
    fun normalizeFeedUrl_acceptsHttpAndRejectsOtherSchemes() {
        assertEquals(
            "https://example.com/show/feed.xml",
            normalizeFeedUrl("  https://example.com/show/feed.xml  "),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeFeedUrl("file:///tmp/feed.xml")
        }
    }

    @Test
    fun generatedIdsAreStableAndSourceScoped() {
        val feed = "https://example.com/feed.xml"
        assertEquals(podcastIdForFeed(feed), podcastIdForFeed(feed))
        assertEquals(
            episodeIdForFeed(feed, "episode-1", "https://cdn.example.com/1.mp3"),
            episodeIdForFeed(feed, "episode-1", "https://cdn.example.com/moved.mp3"),
        )
        assertNotEquals(
            episodeIdForFeed(feed, null, "https://cdn.example.com/1.mp3"),
            episodeIdForFeed(feed, null, "https://cdn.example.com/2.mp3"),
        )
    }

    @Test
    fun parsePodcastDuration_supportsCommonRssFormats() {
        assertEquals(3723, parsePodcastDuration("1:02:03"))
        assertEquals(754, parsePodcastDuration("12:34"))
        assertEquals(90, parsePodcastDuration("90"))
        assertEquals(3723, parsePodcastDuration("1h 2m 3s"))
        assertEquals(-1, parsePodcastDuration(null))
    }

    @Test
    fun parsePodcastDate_supportsRfcAndIsoDates() {
        assertNotNull(parsePodcastDate("Wed, 02 Oct 2002 08:00:00 +0000"))
        assertNotNull(parsePodcastDate("2025-01-02T03:04:05Z"))
    }

    @Test
    fun rssPlaybackMetadata_survivesDatabaseAndQueueMappings() {
        val publishedAt = LocalDateTime.of(2025, 3, 1, 12, 30)
        val metadata = MediaMetadata(
            id = "rss_episode:123",
            title = "Episode title",
            artists = listOf(MediaMetadata.Artist(id = "rss_feed:456", name = "Show author")),
            duration = 3600,
            album = MediaMetadata.Album(id = "rss_feed:456", title = "Show title"),
            isEpisode = true,
            mediaUrl = "https://cdn.example.com/episode.mp3",
            shareUrl = "https://example.com/episodes/123",
            description = "Episode description",
            publishedAt = publishedAt,
        )

        val entity = metadata.toSongEntity()
        assertEquals(true, entity.isEpisode)
        assertEquals(metadata.mediaUrl, entity.mediaUrl)
        assertEquals(metadata.shareUrl, entity.shareUrl)
        assertEquals(metadata.description, entity.description)
        assertEquals(publishedAt, entity.date)

        val bytes = ByteArrayOutputStream().use { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(metadata) }
            buffer.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as MediaMetadata
        }
        assertEquals(metadata, restored)
    }
}

package com.metrolist.music.podcast

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
}

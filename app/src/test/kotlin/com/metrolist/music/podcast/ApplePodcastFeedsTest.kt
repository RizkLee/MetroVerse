/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplePodcastFeedsTest {
    @Test
    fun appleNewsTodayUsesItsPublicPublisherFeedWhenTheCatalogOmitsIt() {
        val response = json.decodeFromString<ApplePodcastLookupResponse>(
            """{"resultCount":1,"results":[{"collectionId":1473872585,"collectionName":"Apple News Today"}]}""",
        )

        assertEquals(
            "https://apple.news/podcast/apple_news_today",
            resolveApplePodcastFeedUrl("1473872585", response.results.map { it.feedUrl }),
        )
    }

    @Test
    fun anotherCatalogEntryWithTheSameShowNameIsNotSilentlyMerged() {
        assertNull(resolveApplePodcastFeedUrl("1719023922", listOf(null)))
    }

    @Test
    fun catalogFeedTakesPrecedenceOverThePublisherFallback() {
        assertEquals(
            "https://example.com/new-feed.xml",
            resolveApplePodcastFeedUrl("1473872585", listOf("https://example.com/new-feed.xml")),
        )
    }

    @Test
    fun emptyCatalogFieldsAreSkippedAndValidUrlsAreTrimmed() {
        assertEquals(
            "https://example.com/feed.xml",
            resolveApplePodcastFeedUrl("123", listOf(null, "", "  ", " https://example.com/feed.xml ")),
        )
    }

    @Test
    fun blankCatalogFeedStillAllowsTheVerifiedPublisherFallback() {
        assertEquals(
            "https://apple.news/podcast/apple_news_today",
            resolveApplePodcastFeedUrl("1473872585", listOf("  ")),
        )
    }

    @Test
    fun otherShowsWithoutAFeedRemainUnavailable() {
        assertNull(resolveApplePodcastFeedUrl("123", listOf(null, " ")))
        assertNull(resolveApplePodcastFeedUrl("", emptyList()))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

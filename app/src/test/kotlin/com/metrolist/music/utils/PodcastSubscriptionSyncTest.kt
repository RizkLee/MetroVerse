package com.metrolist.music.utils

import com.metrolist.music.db.entities.PodcastEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastSubscriptionSyncTest {
    @Test
    fun `open RSS subscriptions are never removed by YouTube sync`() {
        val rssPodcast = PodcastEntity(
            id = "MPSP-id-shape-does-not-matter",
            title = "Open RSS podcast",
            feedUrl = "https://example.com/feed.xml",
        )

        assertTrue(
            youtubePodcastSubscriptionRemovalCandidates(
                localPodcasts = listOf(rssPodcast),
                remoteIds = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `YouTube sync removes only missing YouTube managed podcasts`() {
        val presentYouTubePodcast = PodcastEntity(
            id = "MPSP-present",
            title = "Present YouTube podcast",
        )
        val missingYouTubePodcast = PodcastEntity(
            id = "MPSP-missing",
            title = "Missing YouTube podcast",
        )
        val rssPodcast = PodcastEntity(
            id = "rss-podcast",
            title = "Open RSS podcast",
            feedUrl = "https://example.com/rss.xml",
        )

        val candidates = youtubePodcastSubscriptionRemovalCandidates(
            localPodcasts = listOf(presentYouTubePodcast, missingYouTubePodcast, rssPodcast),
            remoteIds = setOf(presentYouTubePodcast.id),
        )

        assertEquals(listOf(missingYouTubePodcast.id), candidates.map(PodcastEntity::id))
    }
}

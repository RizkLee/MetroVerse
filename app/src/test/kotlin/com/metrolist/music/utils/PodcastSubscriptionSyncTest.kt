package com.metrolist.music.utils

import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
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

    @Test
    fun `empty YouTube saved episodes never removes RSS favorites`() {
        val rssEpisode = savedEpisode(
            id = "MPSP-id-shape-does-not-matter",
            mediaUrl = "https://example.com/episode.mp3",
        )

        assertTrue(
            youtubeSavedEpisodeRemovalCandidates(
                localSavedEpisodes = listOf(rssEpisode),
                remoteIds = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `saved episode cleanup removes only missing YouTube episodes`() {
        val presentYouTubeEpisode = savedEpisode("present")
        val missingYouTubeEpisode = savedEpisode("missing")
        val rssEpisode = savedEpisode("rss-episode", "https://example.com/episode.mp3")

        val candidates = youtubeSavedEpisodeRemovalCandidates(
            localSavedEpisodes = listOf(presentYouTubeEpisode, missingYouTubeEpisode, rssEpisode),
            remoteIds = setOf(presentYouTubeEpisode.id),
        )

        assertEquals(listOf(missingYouTubeEpisode.id), candidates.map(SongEntity::id))
    }

    @Test
    fun `saved episode cleanup uses media ownership rather than ID prefixes`() {
        val youtubeEpisode = savedEpisode("rss-looking-id")
        val directEpisode = savedEpisode("youtube-looking-id", "https://example.com/audio.mp3")

        val candidates = youtubeSavedEpisodeRemovalCandidates(
            localSavedEpisodes = listOf(youtubeEpisode, directEpisode),
            remoteIds = emptySet(),
        )

        assertEquals(listOf(youtubeEpisode.id), candidates.map(SongEntity::id))
    }

    private fun savedEpisode(id: String, mediaUrl: String? = null) = SongEntity(
        id = id,
        title = "Saved episode",
        isEpisode = true,
        inLibrary = LocalDateTime.of(2026, 10, 7, 12, 0),
        mediaUrl = mediaUrl,
    )
}

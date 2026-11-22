/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicVideoType
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.isPodcastBrowseId
import com.metrolist.innertube.models.isYouTubePodcast
import com.metrolist.innertube.models.parseMusicVideoType
import com.metrolist.innertube.pages.BrowseResult
import com.metrolist.innertube.pages.ChartsPage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.BlockAiSlopArtistsKey
import com.metrolist.music.constants.ExplicitOnlyKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideNonMusicalVideosKey
import com.metrolist.music.constants.HidePodcastsKey
import com.metrolist.music.constants.HideShortSongsKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.MinimumSongDurationSecondsKey
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.db.entities.SongWithStats
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

fun SongEntity.isOpenRssEpisode(): Boolean = !mediaUrl.isNullOrBlank() || id.startsWith("rss_episode_")

fun Song.isOpenRssEpisode(): Boolean = song.isOpenRssEpisode()

fun MediaMetadata.isOpenRssEpisode(): Boolean = !mediaUrl.isNullOrBlank() || id.startsWith("rss_episode_")

fun PodcastEntity.isOpenRssPodcast(): Boolean = !feedUrl.isNullOrBlank() || id.startsWith("rss_podcast_")

data class ContentFilterSnapshot(
    val hideExplicit: Boolean = false,
    val explicitOnly: Boolean = false,
    val blockAiSlopArtists: Boolean = false,
    val hideVideoSongs: Boolean = false,
    val hideNonMusicalVideos: Boolean = false,
    val hideShortSongs: Boolean = false,
    val minimumSongDurationSeconds: Int = DEFAULT_MINIMUM_SONG_DURATION_SECONDS,
    val hidePodcasts: Boolean = false,
    val aiSlopEntries: Set<String> = emptySet(),
) {
    val effectiveMinimumDurationSeconds: Int
        get() = minimumSongDurationSeconds.coerceIn(MIN_SONG_DURATION_SECONDS, MAX_SONG_DURATION_SECONDS)

    private fun isExplicitFiltered(
        isExplicit: Boolean,
        ignoreExplicitOnly: Boolean = false,
    ): Boolean =
        (!explicitOnly && hideExplicit && isExplicit) ||
            (explicitOnly && !ignoreExplicitOnly && !isExplicit)

    private fun isShortDuration(durationSeconds: Int?): Boolean =
        hideShortSongs &&
            durationSeconds != null &&
            durationSeconds > 0 &&
            durationSeconds < effectiveMinimumDurationSeconds

    private fun matchesAiSlop(
        channelId: String?,
        name: String?,
    ): Boolean =
        blockAiSlopArtists &&
            aiSlopEntries.isNotEmpty() &&
            AiSlopBlocklist.matches(channelId, name, aiSlopEntries)

    fun shouldHide(item: YTItem): Boolean =
        when (item) {
            is SongItem -> {
                val isYtPodcast = item.isYouTubePodcast
                isExplicitFiltered(item.explicit, ignoreExplicitOnly = isYtPodcast) ||
                    (hideVideoSongs && item.isVideoSong) ||
                    (hideNonMusicalVideos && item.isNonMusicalVideo) ||
                    (hidePodcasts && isYtPodcast) ||
                    (!isYtPodcast && isShortDuration(item.duration)) ||
                    item.artists.any { matchesAiSlop(it.id, it.name) }
            }

            is AlbumItem -> {
                isExplicitFiltered(item.explicit) ||
                    item.artists.orEmpty().any { matchesAiSlop(it.id, it.name) }
            }

            is ArtistItem -> {
                matchesAiSlop(item.id, item.title)
            }

            is PodcastItem -> {
                hidePodcasts || matchesAiSlop(item.channelId ?: item.author?.id, item.author?.name)
            }

            is EpisodeItem -> {
                hidePodcasts || matchesAiSlop(item.author?.id, item.author?.name)
            }

            is PlaylistItem -> {
                (hidePodcasts && item.isYouTubePodcast) ||
                    (hideShortSongs && item.id.startsWith("SS")) ||
                    matchesAiSlop(item.author?.id, item.author?.name)
            }
        }

    fun shouldHide(song: Song): Boolean {
        // Open RSS episodes belong to the user's independent RSS feeds and are never filtered by YT/music filters.
        if (song.isOpenRssEpisode()) return false
        return (hidePodcasts && song.song.isEpisode) ||
            isExplicitFiltered(song.song.explicit, ignoreExplicitOnly = song.song.isEpisode) ||
            (hideVideoSongs && !song.song.isUploaded && song.song.isVideo && !song.song.isEpisode) ||
            (!song.song.isEpisode && isShortDuration(song.song.duration)) ||
            song.artists.any { matchesAiSlop(it.id, it.name) }
    }

    fun shouldHide(song: SongWithStats): Boolean {
        if (song.id.startsWith("rss_episode_")) return false
        return (hideVideoSongs && song.isVideo) ||
            song.artists.any { matchesAiSlop(it.id, it.name) } ||
            matchesAiSlop(null, song.artistName)
    }

    fun shouldHide(album: Album): Boolean =
        isExplicitFiltered(album.album.explicit) ||
            album.artists.any { matchesAiSlop(it.id, it.name) }

    fun shouldHide(artist: Artist): Boolean =
        matchesAiSlop(artist.id, artist.artist.name)

    fun shouldHide(playlist: Playlist): Boolean =
        (hidePodcasts && playlist.playlist.browseId.isPodcastBrowseId()) ||
            (hideShortSongs && playlist.playlist.browseId?.startsWith("SS") == true)

    fun shouldHide(podcast: PodcastEntity): Boolean {
        if (podcast.isOpenRssPodcast()) return false
        return hidePodcasts || matchesAiSlop(podcast.channelId, podcast.author)
    }

    fun shouldHide(metadata: MediaMetadata): Boolean {
        if (metadata.isOpenRssEpisode()) return false
        val isYtPodcast =
            metadata.isEpisode ||
                parseMusicVideoType(metadata.musicVideoType) == MusicVideoType.PODCAST_EPISODE
        return (hidePodcasts && isYtPodcast) ||
            isExplicitFiltered(metadata.explicit, ignoreExplicitOnly = isYtPodcast) ||
            (hideVideoSongs && !isYtPodcast && metadata.isVideoSong) ||
            (hideNonMusicalVideos && !isYtPodcast && metadata.isNonMusicalVideo) ||
            (!isYtPodcast && isShortDuration(metadata.duration)) ||
            metadata.artists.any { matchesAiSlop(it.id, it.name) }
    }

    fun <T : YTItem> filterItems(items: List<T>): List<T> = items.filterNot(::shouldHide)

    fun filterHomePage(page: HomePage): HomePage {
        val filteredChips =
            page.chips?.let { chips ->
                if (hidePodcasts) chips.filterNot { it.isPodcastChip } else chips
            }
        val filteredSections =
            page.sections.mapNotNull { section ->
                if (hidePodcasts && section.endpoint?.browseId.isPodcastBrowseId()) {
                    return@mapNotNull null
                }
                val items = filterItems(section.items)
                if (items.isEmpty()) null else section.copy(items = items)
            }
        return page.copy(chips = filteredChips, sections = filteredSections)
    }

    fun filterSearchSummaryPage(page: SearchSummaryPage): SearchSummaryPage =
        page.copy(
            summaries =
                page.summaries.mapNotNull { summary ->
                    val items = filterItems(summary.items)
                    if (items.isEmpty()) null else summary.copy(items = items)
                },
        )

    fun filterBrowseResult(result: BrowseResult): BrowseResult =
        result.copy(
            items =
                result.items.mapNotNull { group ->
                    val items = filterItems(group.items)
                    if (items.isEmpty()) null else group.copy(items = items)
                },
        )

    fun filterChartsPage(page: ChartsPage): ChartsPage =
        page.copy(
            sections =
                page.sections.mapNotNull { section ->
                    val items = filterItems(section.items)
                    if (items.isEmpty()) null else section.copy(items = items)
                },
        )

    fun filterSongs(songs: List<Song>): List<Song> = songs.filterNot(::shouldHide)

    fun filterSongsWithStats(songs: List<SongWithStats>): List<SongWithStats> = songs.filterNot(::shouldHide)

    fun filterAlbums(albums: List<Album>): List<Album> = albums.filterNot(::shouldHide)

    fun filterArtists(artists: List<Artist>): List<Artist> = artists.filterNot(::shouldHide)

    fun filterPlaylists(playlists: List<Playlist>): List<Playlist> = playlists.filterNot(::shouldHide)

    fun filterPodcasts(podcasts: List<PodcastEntity>): List<PodcastEntity> = podcasts.filterNot(::shouldHide)

    fun filterMediaItems(items: List<MediaItem>): List<MediaItem> =
        items.filterNot { mediaItem ->
            mediaItem.metadata?.let(::shouldHide) == true
        }

    fun filterQueueStatus(status: Queue.Status): Queue.Status =
        status.filter { mediaItem ->
            mediaItem.metadata?.let(::shouldHide) != true
        }

    companion object {
        const val DEFAULT_MINIMUM_SONG_DURATION_SECONDS = 30
        const val MIN_SONG_DURATION_SECONDS = 15
        const val MAX_SONG_DURATION_SECONDS = 180
    }
}

object ContentFilter {
    private data class PreferenceState(
        val hideExplicit: Boolean,
        val explicitOnly: Boolean,
        val blockAiSlopArtists: Boolean,
        val hideVideoSongs: Boolean,
        val hideNonMusicalVideos: Boolean,
        val hideShortSongs: Boolean,
        val minimumSongDurationSeconds: Int,
        val hidePodcasts: Boolean,
    )

    private fun preferenceState(prefs: Preferences): PreferenceState {
        val rawHideExplicit = prefs[HideExplicitKey] ?: false
        val rawExplicitOnly = prefs[ExplicitOnlyKey] ?: false
        val hideShortSongs =
            prefs[HideShortSongsKey] ?: (prefs[HideYoutubeShortsKey] ?: false)
        val minDuration =
            (prefs[MinimumSongDurationSecondsKey] ?: ContentFilterSnapshot.DEFAULT_MINIMUM_SONG_DURATION_SECONDS)
                .coerceIn(
                    ContentFilterSnapshot.MIN_SONG_DURATION_SECONDS,
                    ContentFilterSnapshot.MAX_SONG_DURATION_SECONDS,
                )
        return PreferenceState(
            hideExplicit = rawHideExplicit,
            explicitOnly = if (rawHideExplicit) false else rawExplicitOnly,
            blockAiSlopArtists = prefs[BlockAiSlopArtistsKey] ?: false,
            hideVideoSongs = prefs[HideVideoSongsKey] ?: false,
            hideNonMusicalVideos = prefs[HideNonMusicalVideosKey] ?: false,
            hideShortSongs = hideShortSongs,
            minimumSongDurationSeconds = minDuration,
            hidePodcasts = prefs[HidePodcastsKey] ?: false,
        )
    }

    fun fromPreferences(
        prefs: Preferences,
        aiSlopEntries: Set<String> = AiSlopBlocklist.entries.value,
    ): ContentFilterSnapshot {
        val state = preferenceState(prefs)
        return ContentFilterSnapshot(
            hideExplicit = state.hideExplicit,
            explicitOnly = state.explicitOnly,
            blockAiSlopArtists = state.blockAiSlopArtists,
            hideVideoSongs = state.hideVideoSongs,
            hideNonMusicalVideos = state.hideNonMusicalVideos,
            hideShortSongs = state.hideShortSongs,
            minimumSongDurationSeconds = state.minimumSongDurationSeconds,
            hidePodcasts = state.hidePodcasts,
            aiSlopEntries = aiSlopEntries,
        )
    }

    fun observe(context: Context): Flow<ContentFilterSnapshot> =
        combine(
            context.dataStore.data
                .map(::preferenceState)
                .distinctUntilChanged(),
            AiSlopBlocklist.entries,
        ) { state, aiSlopEntries ->
            ContentFilterSnapshot(
                hideExplicit = state.hideExplicit,
                explicitOnly = state.explicitOnly,
                blockAiSlopArtists = state.blockAiSlopArtists,
                hideVideoSongs = state.hideVideoSongs,
                hideNonMusicalVideos = state.hideNonMusicalVideos,
                hideShortSongs = state.hideShortSongs,
                minimumSongDurationSeconds = state.minimumSongDurationSeconds,
                hidePodcasts = state.hidePodcasts,
                aiSlopEntries = aiSlopEntries,
            )
        }.distinctUntilChanged()

    suspend fun current(context: Context): ContentFilterSnapshot =
        fromPreferences(context.dataStore.data.first(), AiSlopBlocklist.entries.value)

    fun currentBlocking(context: Context): ContentFilterSnapshot =
        runBlocking { current(context) }
}

@Composable
fun rememberContentFilter(): ContentFilterSnapshot {
    val context = LocalContext.current
    val flow = remember(context) { ContentFilter.observe(context) }
    val snapshot by flow.collectAsStateWithLifecycle(
        initialValue = ContentFilterSnapshot(),
    )
    return snapshot
}

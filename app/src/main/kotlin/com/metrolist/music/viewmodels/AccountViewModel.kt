/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.utils.completed
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.ContentFilter
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AccountContentType {
    PLAYLISTS, ALBUMS, ARTISTS, PODCASTS
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    private val contentFilter = ContentFilter.observe(context)
    private val rawPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    private val rawAlbums = MutableStateFlow<List<AlbumItem>?>(null)
    private val rawArtists = MutableStateFlow<List<ArtistItem>?>(null)
    private val rawPodcastChannels = MutableStateFlow<List<ArtistItem>>(emptyList())

    val playlists =
        combine(rawPlaylists, contentFilter) { items, filter ->
            items?.let(filter::filterItems)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val albums =
        combine(rawAlbums, contentFilter) { items, filter ->
            items?.let(filter::filterItems)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val artists =
        combine(rawArtists, contentFilter) { items, filter ->
            items?.let(filter::filterItems)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    // SE "Episodes for Later" playlist shown in Podcasts tab
    val sePlaylist = MutableStateFlow<PlaylistItem?>(null)
    // RDPN "New Episodes" playlist (real thumbnail + count from YouTube)
    val rdpnPlaylist = MutableStateFlow<PlaylistItem?>(null)
    // Subscribed podcast shows (from local DB, synced from YT Music; Open RSS kept exempt)
    val podcastPlaylists =
        combine(database.subscribedPodcasts(), contentFilter) { podcasts, filter ->
            filter.filterPodcasts(podcasts)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    // Podcast host channels from YT Music library
    val podcastChannels =
        combine(rawPodcastChannels, contentFilter) { channels, filter ->
            if (filter.hidePodcasts) emptyList() else filter.filterItems(channels)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Selected content type for chips
    val selectedContentType = MutableStateFlow(AccountContentType.PLAYLISTS)

    private suspend fun loadPlaylists() {
        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
            val all = it.items.filterIsInstance<PlaylistItem>()
            // Extract SE playlist separately for Podcasts tab
            sePlaylist.value = all.find { it.id == "SE" }
            rawPlaylists.value = all.filterNot { it.id == "SE" }
        }.onFailure {
            reportException(it)
        }
    }

    init {
        viewModelScope.launch {
            loadPlaylists()
            YouTube.library("FEmusic_liked_albums").completed().onSuccess {
                rawAlbums.value = it.items.filterIsInstance<AlbumItem>()
            }.onFailure {
                reportException(it)
            }
            YouTube.library("FEmusic_library_corpus_artists").completed().onSuccess {
                rawArtists.value = it.items.filterIsInstance<ArtistItem>().map { artist ->
                    artist.copy(
                        thumbnail = artist.thumbnail?.resize(544, 544)
                    )
                }
            }.onFailure {
                reportException(it)
            }
        }
        viewModelScope.launch {
            YouTube.newEpisodesPlaylistInfo().onSuccess {
                rdpnPlaylist.value = it
            }.onFailure {
                reportException(it)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            YouTube.libraryPodcastChannels().onSuccess {
                rawPodcastChannels.value = it.items.filterIsInstance<ArtistItem>()
            }.onFailure {
                reportException(it)
            }
        }
    }

    fun setSelectedContentType(contentType: AccountContentType) {
        selectedContentType.value = contentType
    }
}

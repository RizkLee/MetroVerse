/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.utils.ContentFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalSearchViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LocalFilter.ALL)

    val result =
        combine(
            query,
            filter,
            ContentFilter.observe(context),
        ) { query, filter, contentFilter ->
            Triple(query, filter, contentFilter)
        }.flatMapLatest { (query, filter, contentFilter) ->
            if (query.isEmpty()) {
                flowOf(LocalSearchResult("", filter, emptyMap()))
            } else {
                when (filter) {
                    LocalFilter.ALL -> {
                        val mediaResults = combine(
                            database.searchSongs(query, PREVIEW_SIZE),
                            database.searchAlbums(query, PREVIEW_SIZE),
                            database.searchArtists(query, PREVIEW_SIZE),
                            database.searchPlaylists(query, PREVIEW_SIZE),
                        ) { songs, albums, artists, playlists ->
                            contentFilter.filterSongs(songs) +
                                contentFilter.filterAlbums(albums) +
                                contentFilter.filterArtists(artists) +
                                contentFilter.filterPlaylists(playlists)
                        }
                        combine(
                            mediaResults,
                            database.searchPodcasts(query, PREVIEW_SIZE),
                        ) { items, podcasts ->
                            LocalSearchResult(
                                query = query,
                                filter = filter,
                                map = items.groupByLocalFilter(),
                                podcasts = contentFilter.filterPodcasts(podcasts),
                            )
                        }
                    }

                    LocalFilter.PODCAST -> database.searchPodcasts(query).map { podcasts ->
                        LocalSearchResult(
                            query = query,
                            filter = filter,
                            map = emptyMap(),
                            podcasts = contentFilter.filterPodcasts(podcasts),
                        )
                    }

                    else -> {
                        val items = when (filter) {
                            LocalFilter.SONG -> database.searchSongs(query).map(contentFilter::filterSongs)
                            LocalFilter.ALBUM -> database.searchAlbums(query).map(contentFilter::filterAlbums)
                            LocalFilter.ARTIST -> database.searchArtists(query).map(contentFilter::filterArtists)
                            LocalFilter.PLAYLIST -> database.searchPlaylists(query).map(contentFilter::filterPlaylists)
                            LocalFilter.ALL, LocalFilter.PODCAST -> error("Handled above")
                        }
                        items.map { mediaItems ->
                            LocalSearchResult(
                                query = query,
                                filter = filter,
                                map = mediaItems.groupByLocalFilter(),
                            )
                        }
                    }
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            LocalSearchResult("", filter.value, emptyMap()),
        )

    companion object {
        const val PREVIEW_SIZE = 3
    }
}

enum class LocalFilter {
    ALL,
    SONG,
    ALBUM,
    ARTIST,
    PLAYLIST,
    PODCAST,
}

data class LocalSearchResult(
    val query: String,
    val filter: LocalFilter,
    val map: Map<LocalFilter, List<LocalItem>>,
    val podcasts: List<PodcastEntity> = emptyList(),
)

private fun List<LocalItem>.groupByLocalFilter(): Map<LocalFilter, List<LocalItem>> =
    groupBy { item ->
        when (item) {
            is Song -> LocalFilter.SONG
            is Album -> LocalFilter.ALBUM
            is Artist -> LocalFilter.ARTIST
            is Playlist -> LocalFilter.PLAYLIST
        }
    }

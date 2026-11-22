/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.utils.completed
import com.metrolist.music.constants.AlbumFilter
import com.metrolist.music.constants.AlbumFilterKey
import com.metrolist.music.constants.AlbumSortDescendingKey
import com.metrolist.music.constants.AlbumSortType
import com.metrolist.music.constants.AlbumSortTypeKey
import com.metrolist.music.constants.ArtistFilter
import com.metrolist.music.constants.ArtistFilterKey
import com.metrolist.music.constants.ArtistSongSortDescendingKey
import com.metrolist.music.constants.ArtistSongSortType
import com.metrolist.music.constants.ArtistSongSortTypeKey
import com.metrolist.music.constants.ArtistSortDescendingKey
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.ArtistSortTypeKey
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.constants.PlaylistSortDescendingKey
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.constants.PlaylistSortTypeKey
import com.metrolist.music.constants.SongFilter
import com.metrolist.music.constants.SongFilterKey
import com.metrolist.music.constants.SongSortDescendingKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.constants.TopSize
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.deduplicateByBrowseId
import com.metrolist.music.extensions.matchesNormalizedQuery
import com.metrolist.music.extensions.normalizeForSearch
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.utils.ContentFilter
import com.metrolist.music.utils.FullSyncResult
import com.metrolist.music.utils.PodcastRefreshTrigger
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class LibrarySongsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allSongs =
        combine(
            context.dataStore.data
                .map {
                    Triple(
                        it[SongFilterKey].toEnum(SongFilter.LIKED),
                        it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                        (it[SongSortDescendingKey] ?: true),
                    )
                }.distinctUntilChanged(),
            ContentFilter.observe(context),
        ) { filterSort, contentFilter ->
            filterSort to contentFilter
        }.flatMapLatest { (filterSort, contentFilter) ->
            val (filter, sortType, descending) = filterSort
            when (filter) {
                SongFilter.LIBRARY -> database.songs(sortType, descending).map(contentFilter::filterSongs)
                SongFilter.LIKED -> database.likedSongs(sortType, descending).map(contentFilter::filterSongs)
                SongFilter.DOWNLOADED -> database.downloadedSongs(sortType, descending).map(contentFilter::filterSongs)
                SongFilter.UPLOADED -> database.uploadedSongs(sortType, descending).map(contentFilter::filterSongs)
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun syncLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLikedSongs() }
    }

    fun syncLibrarySongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLibrarySongs() }
    }

    fun syncUploadedSongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncUploadedSongs() }
    }
}

@HiltViewModel
class LibraryArtistsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allArtists =
        combine(
            context.dataStore.data
                .map {
                    Triple(
                        it[ArtistFilterKey].toEnum(ArtistFilter.LIKED),
                        it[ArtistSortTypeKey].toEnum(ArtistSortType.CREATE_DATE),
                        it[ArtistSortDescendingKey] ?: true,
                    )
                }.distinctUntilChanged(),
            ContentFilter.observe(context),
        ) { filterSort, contentFilter ->
            filterSort to contentFilter
        }.flatMapLatest { (filterSort, contentFilter) ->
            val (filter, sortType, descending) = filterSort
            when (filter) {
                ArtistFilter.LIKED -> database.artistsBookmarked(sortType, descending).map(contentFilter::filterArtists)
                ArtistFilter.LIBRARY -> database.artists(sortType, descending).map(contentFilter::filterArtists)
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val filteredArtists =
        combine(allArtists, searchQuery) { artists, query ->
            val normalizedQuery = query.normalizeForSearch()
            artists
                .filter { artist ->
                    matchesNormalizedQuery(normalizedQuery, artist.artist.name)
                }
                .distinctBy { it.id }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncArtistsSubscriptions() }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }.take(5)
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryAlbumsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allAlbums =
        combine(
            context.dataStore.data
                .map {
                    Triple(
                        it[AlbumFilterKey].toEnum(AlbumFilter.LIKED),
                        it[AlbumSortTypeKey].toEnum(AlbumSortType.CREATE_DATE),
                        it[AlbumSortDescendingKey] ?: true,
                    )
                }.distinctUntilChanged(),
            ContentFilter.observe(context),
        ) { filterSort, contentFilter ->
            filterSort to contentFilter
        }.flatMapLatest { (filterSort, contentFilter) ->
            val (filter, sortType, descending) = filterSort
            when (filter) {
                AlbumFilter.LIKED -> database.albumsLiked(sortType, descending).map(contentFilter::filterAlbums)
                AlbumFilter.LIBRARY -> database.albums(sortType, descending).map(contentFilter::filterAlbums)
                AlbumFilter.UPLOADED -> database.albumsUploaded(sortType, descending).map(contentFilter::filterAlbums)
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLikedAlbums() }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allAlbums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.take(5)
                    .forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                            }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryPlaylistsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allPlaylists =
        combine(
            context.dataStore.data
                .map {
                    it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE) to
                        (it[PlaylistSortDescendingKey] ?: true)
                }.distinctUntilChanged(),
            ContentFilter.observe(context),
        ) { sortDesc, contentFilter ->
            sortDesc to contentFilter
        }.flatMapLatest { (sortDesc, contentFilter) ->
            val (sortType, descending) = sortDesc
            database.playlists(sortType, descending).map {
                contentFilter.filterPlaylists(it).deduplicateByBrowseId()
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncSavedPlaylists() }
    }

    val topValue =
        context.dataStore.data
            .map { it[TopSize] ?: "50" }
            .distinctUntilChanged()
}

@HiltViewModel
class ArtistSongsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")!!
    val artist =
        database
            .artist(artistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs =
        combine(
            context.dataStore.data
                .map {
                    it[ArtistSongSortTypeKey].toEnum(ArtistSongSortType.CREATE_DATE) to
                        (it[ArtistSongSortDescendingKey] ?: true)
                }.distinctUntilChanged(),
            ContentFilter.observe(context),
        ) { sortDesc, contentFilter ->
            sortDesc to contentFilter
        }.flatMapLatest { (sortDesc, contentFilter) ->
            val (sortType, descending) = sortDesc
            database.artistSongs(artistId, sortType, descending).map(contentFilter::filterSongs)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class LibraryMixViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val syncAllLibrary = {
         viewModelScope.launch(Dispatchers.IO) {
             syncUtils.tryAutoSync()
         }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshNow()
        }
    }

    suspend fun refreshNow(): FullSyncResult {
        _isRefreshing.value = true
        return try {
            withContext(Dispatchers.IO) {
                syncUtils.performFullSyncSuspend()
            }
        } finally {
            _isRefreshing.value = false
        }
    }

    val topValue =
        context.dataStore.data
            .map { it[TopSize] ?: "50" }
            .distinctUntilChanged()
    var artists =
        ContentFilter.observe(context)
            .flatMapLatest { contentFilter ->
                database
                    .artistsBookmarked(ArtistSortType.CREATE_DATE, true)
                    .map(contentFilter::filterArtists)
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var albums =
        ContentFilter.observe(context)
            .flatMapLatest { contentFilter ->
                database.albumsLiked(AlbumSortType.CREATE_DATE, true).map(contentFilter::filterAlbums)
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var songs =
        ContentFilter.observe(context)
            .flatMapLatest { contentFilter ->
                combine(
                    database.songs(SongSortType.CREATE_DATE, true),
                    database.songsInBookmarkedPlaylists()
                ) { librarySongs, playlistSongs ->
                    contentFilter.filterSongs((librarySongs + playlistSongs).distinctBy { it.id })
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var playlists =
        ContentFilter.observe(context)
            .flatMapLatest { contentFilter ->
                database.playlists(PlaylistSortType.CREATE_DATE, true).map {
                    contentFilter.filterPlaylists(it).deduplicateByBrowseId()
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val likedAutoPlaylistSongs =
        database
            .likedSongs(SongSortType.CREATE_DATE, true)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val downloadedAutoPlaylistSongs =
        database
            .downloadedSongs(SongSortType.CREATE_DATE, true)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val uploadedAutoPlaylistSongs =
        database
            .uploadedSongsByCreateDateAsc()
            .map(List<Song>::reversed)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            albums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.take(5)
                    .forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                            }
                    }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            artists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null ||
                                Duration.between(
                                    it.lastUpdateTime,
                                    LocalDateTime.now(),
                                ) > Duration.ofDays(10)
                    }.take(5)
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryPodcastsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    // Subscribed podcast shows (YT Music shows hidden if hidePodcasts is enabled; Open RSS always kept)
    val subscribedChannels =
        kotlinx.coroutines.flow.combine(
            database.subscribedPodcasts(),
            ContentFilter.observe(context),
        ) { podcasts, contentFilter ->
            contentFilter.filterPodcasts(podcasts)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // SE "Episodes for Later" playlist fetched from YT Music (like AccountScreen)
    private val _sePlaylist = MutableStateFlow<com.metrolist.innertube.models.PlaylistItem?>(null)
    val sePlaylist = _sePlaylist.asStateFlow()

    // RDPN "New Episodes" playlist fetched from YouTube Music (real thumbnail + episode count)
    private val _rdpnPlaylist = MutableStateFlow<com.metrolist.innertube.models.PlaylistItem?>(null)
    val rdpnPlaylist = _rdpnPlaylist.asStateFlow()

    // Podcast host channels fetched from YT Music library/podcast_channels
    private val _apiPodcastChannels = MutableStateFlow<List<ArtistItem>>(emptyList())

    // Podcast channels: API subscriptions + locally bookmarked artists that have podcasts
    // Only shows channels explicitly subscribed to (not derived from saved podcasts)
    val podcastChannels = kotlinx.coroutines.flow.combine(
        _apiPodcastChannels,
        database.bookmarkedPodcastChannels(),
        ContentFilter.observe(context),
    ) { apiChannels, localPodcastChannels, contentFilter ->
        if (contentFilter.hidePodcasts) {
            emptyList()
        } else {
            // Convert locally bookmarked podcast channels to ArtistItem format
            val localAsArtistItems = localPodcastChannels.map { artist ->
                ArtistItem(
                    id = artist.id,
                    title = artist.artist.name,
                    thumbnail = artist.artist.thumbnailUrl,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                )
            }

            // Combine and deduplicate by ID (prefer API version if exists)
            val apiIds = apiChannels.map { it.id }.toSet()
            val uniqueLocalChannels = localAsArtistItems.filter { it.id !in apiIds }
            contentFilter.filterItems(apiChannels + uniqueLocalChannels)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Downloaded podcast episodes (Open RSS episodes strictly exempt)
    val downloadedEpisodes =
        kotlinx.coroutines.flow.combine(
            context.dataStore.data
                .map {
                    it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey] ?: true)
                }.distinctUntilChanged(),
            ContentFilter.observe(context),
        ) { sortDesc, contentFilter ->
            sortDesc to contentFilter
        }.flatMapLatest { (sortDesc, contentFilter) ->
            val (sortType, descending) = sortDesc
            database.downloadedPodcastEpisodes(sortType, descending).map(contentFilter::filterSongs)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Saved podcast episodes (Open RSS episodes strictly exempt)
    val savedEpisodes =
        kotlinx.coroutines.flow.combine(
            context.dataStore.data
                .map {
                    it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey] ?: true)
                }.distinctUntilChanged(),
            ContentFilter.observe(context),
        ) { sortDesc, contentFilter ->
            sortDesc to contentFilter
        }.flatMapLatest { (sortDesc, contentFilter) ->
            val (sortType, descending) = sortDesc
            database.savedPodcastEpisodes(sortType, descending).map(contentFilter::filterSongs)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun fetchSePlaylist() {
        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
            _sePlaylist.value = it.items
                .filterIsInstance<com.metrolist.innertube.models.PlaylistItem>()
                .find { it.id == "SE" }
        }.onFailure {
            timber.log.Timber.e(it, "[PODCAST] Failed to fetch SE playlist")
        }
    }

    private suspend fun fetchPodcastChannels() {
        YouTube.libraryPodcastChannels().onSuccess { page ->
            val channels = page.items.filterIsInstance<ArtistItem>()
            _apiPodcastChannels.value = channels
            timber.log.Timber.d("[PODCAST] Fetched ${channels.size} podcast channels from YT Music")
        }.onFailure {
            timber.log.Timber.e(it, "[PODCAST] Failed to fetch podcast channels")
        }
    }

    private suspend fun fetchRdpnPlaylist() {
        YouTube.newEpisodesPlaylistInfo().onSuccess { item ->
            _rdpnPlaylist.value = item
            timber.log.Timber.d("[PODCAST] RDPN playlist: ${item.title}, thumbnail: ${item.thumbnail}")
        }.onFailure {
            timber.log.Timber.e(it, "[PODCAST] Failed to fetch RDPN playlist info")
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            fetchSePlaylist()
        }
        viewModelScope.launch(Dispatchers.IO) {
            fetchPodcastChannels()
        }
        viewModelScope.launch(Dispatchers.IO) {
            fetchRdpnPlaylist()
        }
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.syncPodcastSubscriptionsSuspend()
        }
        // Observe refresh trigger for auto-refresh after subscribe/unsubscribe
        viewModelScope.launch(Dispatchers.IO) {
            PodcastRefreshTrigger.refreshFlow.collect {
                // Small delay to allow YouTube's backend to update
                kotlinx.coroutines.delay(1500)
                fetchPodcastChannels()
            }
        }
    }

    fun clearPodcastData() {
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.clearPodcastData()
        }
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        fetchSePlaylist()
        fetchPodcastChannels()
        fetchRdpnPlaylist()
        syncUtils.syncPodcastSubscriptionsSuspend()
        syncUtils.syncEpisodesForLaterSuspend()
    }

    /**
     * Force refresh podcast channels. Called when screen becomes visible.
     */
    fun refreshChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            fetchPodcastChannels()
        }
    }
}

@HiltViewModel
class LibraryViewModel
@Inject
constructor() : ViewModel() {
    private val curScreen = mutableStateOf(LibraryFilter.LIBRARY)
    val filter: MutableState<LibraryFilter> = curScreen
}

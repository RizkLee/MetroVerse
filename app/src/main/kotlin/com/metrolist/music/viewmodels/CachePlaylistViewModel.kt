/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.extensions.filterExplicit
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class CachePlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @PlayerCache private val playerCache: Cache,
        @DownloadCache private val downloadCache: Cache,
    ) : ViewModel() {
        private val flaggedSongs = database.cachePlaylistSongs()
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

        @OptIn(ExperimentalCoroutinesApi::class)
        val cachedSongs: StateFlow<List<Song>> =
            observeCachePlaylistCandidates(flaggedSongs, playerCache, downloadCache)
                .mapLatest { flagged ->
                    val partition = partitionCachedSongs(flagged, playerCache, downloadCache)
                    if (partition.stale.isNotEmpty()) {
                        // Only playback/download completion may set this date. Clear stale
                        // dates without overwriting newer download state or other song fields.
                        database.withTransaction {
                            partition.stale.forEach { song ->
                                song.song.dateDownload?.let { expectedDate ->
                                    clearCachePlaylistDateIfUnchanged(song.id, expectedDate)
                                }
                            }
                        }
                    }
                    partition.stillCached
                }
                .combine(
                    context.dataStore.data.map {
                        (it[HideExplicitKey] ?: false) to (it[HideVideoSongsKey] ?: false)
                    }.distinctUntilChanged(),
                ) { songs, (hideExplicit, hideVideoSongs) ->
                    songs
                        .sortedByDescending { it.song.dateDownload }
                        .filterExplicit(hideExplicit)
                        .filterVideoSongs(hideVideoSongs)
                }
                .flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun removeSongFromCache(songId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                playerCache.removeResource(songId)
            }
        }
    }

internal data class CachedSongPartition(
    val stillCached: List<Song>,
    val stale: List<Song>,
)

@OptIn(FlowPreview::class)
internal fun observeCachePlaylistCandidates(
    flaggedSongs: SharedFlow<List<Song>>,
    playerCache: Cache,
    downloadCache: Cache,
): Flow<List<Song>> = combine(
    flaggedSongs,
    observeCachePlaylistChanges(
        flaggedSongs.map { songs -> songs.map { it.id }.toSet() },
        playerCache,
        downloadCache,
    ).conflate().debounce(150),
) { flagged, observedIds ->
    // A new candidate set must have its listeners before its first cache check.
    flagged.takeIf { songs ->
        songs.size == observedIds.size && songs.all { it.id in observedIds }
    }
}.filterNotNull()

internal fun partitionCachedSongs(
    flagged: List<Song>,
    playerCache: Cache,
    downloadCache: Cache,
): CachedSongPartition {
    val stillCached = mutableListOf<Song>()
    val stale = mutableListOf<Song>()
    for (song in flagged) {
        val contentLength = song.format?.contentLength
        val present = song.song.isDownloaded ||
            (contentLength != null &&
                (downloadCache.isCached(song.id, 0, contentLength) ||
                    playerCache.isCached(song.id, 0, contentLength)))
        if (present) stillCached += song else stale += song
    }
    return CachedSongPartition(stillCached, stale)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeCachePlaylistChanges(
    candidateIds: Flow<Set<String>>,
    playerCache: Cache,
    downloadCache: Cache,
): Flow<Set<String>> = candidateIds.distinctUntilChanged().flatMapLatest { songIds ->
    callbackFlow {
        val registrations = mutableListOf<Pair<Cache, String>>()
        val ready = AtomicBoolean(false)
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                if (ready.get()) trySend(songIds)
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                if (ready.get()) trySend(songIds)
            }

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }
        try {
            for (cache in listOf(playerCache, downloadCache)) {
                for (songId in songIds) {
                    ensureActive()
                    registrations += cache to songId
                    cache.addListener(songId, listener)
                }
            }
            // Events during registration are covered by this first check, after both
            // caches have listeners. Callbacks never inspect either cache themselves.
            ready.set(true)
            trySend(songIds)
            awaitClose()
        } finally {
            ready.set(false)
            registrations.forEach { (cache, songId) ->
                runCatching { cache.removeListener(songId, listener) }
            }
        }
    }.catch { cause ->
        if (cause is CancellationException) throw cause
        // Retain Room-driven refreshes if listeners are unavailable, without a retry loop.
        emit(songIds)
    }
}

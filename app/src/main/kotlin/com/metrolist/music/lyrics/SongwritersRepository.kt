/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.lyrics

import android.util.LruCache
import com.metrolist.innertube.YouTube
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.isOpenRssEpisode
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun shouldFetchSongwriters(mediaMetadata: MediaMetadata?): Boolean =
    mediaMetadata != null &&
        !mediaMetadata.isEpisode &&
        !mediaMetadata.isOpenRssEpisode() &&
        mediaMetadata.uploadEntityId == null &&
        mediaMetadata.id.isNotBlank() &&
        !mediaMetadata.id.startsWith("local_")

@Singleton
class SongwritersRepository @Inject constructor() {
    private val cacheLock = Any()
    private val cache = LruCache<String, List<String>>(256)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<String>>>()

    suspend fun getSongwriters(videoId: String): List<String> {
        if (videoId.isBlank()) return emptyList()
        synchronized(cacheLock) { cache.get(videoId) }?.let { return it }

        val newDeferred = CompletableDeferred<List<String>>()
        val existing = inFlight.putIfAbsent(videoId, newDeferred)
        if (existing != null) {
            return existing.await()
        }

        return try {
            val resolved = withContext(Dispatchers.IO) {
                YouTube.songwriters(videoId).getOrThrow()
            }
            synchronized(cacheLock) {
                cache.put(videoId, resolved)
            }
            newDeferred.complete(resolved)
            resolved
        } catch (e: CancellationException) {
            // Avoid failing concurrent waiters permanently if only this caller's coroutine was cancelled.
            newDeferred.cancel(e)
            throw e
        } catch (_: Exception) {
            newDeferred.complete(emptyList())
            emptyList()
        } finally {
            inFlight.remove(videoId, newDeferred)
        }
    }
}

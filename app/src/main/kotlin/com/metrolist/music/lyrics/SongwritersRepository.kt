/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.lyrics

import android.content.Context
import android.util.LruCache
import com.metrolist.innertube.YouTube
import com.metrolist.music.betterlyrics.BetterLyrics
import com.metrolist.music.constants.EnableBetterLyricsKey
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.isOpenRssEpisode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

internal fun shouldFetchSongwriters(mediaMetadata: MediaMetadata?): Boolean =
    mediaMetadata != null &&
        !mediaMetadata.isEpisode &&
        !mediaMetadata.isOpenRssEpisode() &&
        mediaMetadata.uploadEntityId == null &&
        mediaMetadata.id.isNotBlank() &&
        !mediaMetadata.id.startsWith("local_")

/**
 * In-memory and cache-dir JSON store for songwriters extracted from lyrics providers
 * (BetterLyrics TTML and LyricsPlus metadata) without modifying the Room database schema.
 */
internal object ProviderSongwritersCache {
    private const val CACHE_FILE_NAME = "songwriters_cache.json"
    private const val MAX_PERSISTED_ENTRIES = 512

    private val lock = Any()
    private val memory = linkedMapOf<String, List<String>>()
    private var loadedFromDir: String? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mapSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private fun ensureLoadedLocked(cacheDir: File?) {
        val dir = cacheDir ?: return
        val key = dir.absolutePath
        if (loadedFromDir == key) return
        loadedFromDir = key
        val file = File(dir, CACHE_FILE_NAME)
        if (!file.isFile) return
        runCatching {
            val raw = file.readText()
            if (raw.isNotBlank()) {
                val decoded = json.decodeFromString(mapSerializer, raw)
                decoded.forEach { (id, writers) ->
                    val cleaned = writers.map(String::trim).filter(String::isNotEmpty).distinct()
                    if (id.isNotBlank() && cleaned.isNotEmpty() && !memory.containsKey(id)) {
                        memory[id] = cleaned
                    }
                }
            }
        }.onFailure { e ->
            Timber.tag("Songwriters").w("Failed to read persisted songwriters cache (${e::class.simpleName})")
        }
    }

    fun get(context: Context, videoId: String): List<String>? {
        if (videoId.isBlank()) return null
        synchronized(lock) {
            ensureLoadedLocked(context.cacheDir)
            return memory[videoId]?.takeIf { it.isNotEmpty() }
        }
    }

    fun put(context: Context, videoId: String, writers: List<String>) {
        if (videoId.isBlank()) return
        val cleaned = writers.map(String::trim).filter(String::isNotEmpty).distinct()
        if (cleaned.isEmpty()) return
        val cacheDir = context.cacheDir
        val snapshot =
            synchronized(lock) {
                ensureLoadedLocked(cacheDir)
                if (memory[videoId] == cleaned) return
                memory.remove(videoId)
                memory[videoId] = cleaned
                while (memory.size > MAX_PERSISTED_ENTRIES) {
                    val oldest = memory.keys.firstOrNull() ?: break
                    memory.remove(oldest)
                }
                LinkedHashMap(memory)
            }
        runCatching {
            if (cacheDir != null) {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val target = File(cacheDir, CACHE_FILE_NAME)
                val temp = File(cacheDir, "$CACHE_FILE_NAME.tmp")
                temp.writeText(json.encodeToString(mapSerializer, snapshot))
                if (!temp.renameTo(target)) {
                    target.writeText(temp.readText())
                    temp.delete()
                }
            }
        }.onFailure { e ->
            Timber.tag("Songwriters").w("Failed to persist songwriters cache (${e::class.simpleName})")
        }
    }

    fun clearForTest() {
        synchronized(lock) {
            memory.clear()
            loadedFromDir = null
        }
    }
}

@Singleton
class SongwritersRepository internal constructor(
    private val context: Context,
    private val localeTagProvider: () -> String,
    private val fetchYouTubeSongwriters: suspend (String) -> Result<List<String>>,
    private val isBetterLyricsEnabled: () -> Boolean,
    private val fetchBetterLyricsSongwriters: suspend (MediaMetadata) -> List<String>,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        context = context,
        localeTagProvider = { YouTube.locale.hl },
        fetchYouTubeSongwriters = { videoId -> YouTube.songwriters(videoId) },
        isBetterLyricsEnabled = { context.dataStore[EnableBetterLyricsKey] ?: true },
        fetchBetterLyricsSongwriters = { meta ->
            val cleanedTitle = LyricsUtils.cleanTitleForSearch(meta.title)
            val artists = meta.artists.joinToString { it.name }
            BetterLyrics.fetchSongwriters(
                title = cleanedTitle,
                artist = artists,
                duration = meta.duration,
                album = meta.album?.title,
            )
        },
    )

    private val cacheLock = Any()
    private val sessionCache = LruCache<String, List<String>>(256)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<String>>>()

    suspend fun getSongwriters(videoId: String): List<String> {
        if (videoId.isBlank()) return emptyList()
        ProviderSongwritersCache.get(context, videoId)?.let { return it }
        return resolveWithMptc(videoId = videoId, metadata = null)
    }

    suspend fun getSongwriters(mediaMetadata: MediaMetadata?): List<String> {
        if (!shouldFetchSongwriters(mediaMetadata) || mediaMetadata == null) return emptyList()
        val videoId = mediaMetadata.id
        ProviderSongwritersCache.get(context, videoId)?.let { return it }
        return resolveWithMptc(videoId = videoId, metadata = mediaMetadata)
    }

    private suspend fun resolveWithMptc(
        videoId: String,
        metadata: MediaMetadata?,
    ): List<String> {
        val localeTag = localeTagProvider().ifBlank { "default" }
        val sessionKey = "$videoId|$localeTag"
        synchronized(cacheLock) { sessionCache.get(sessionKey) }?.let { return it }

        val newDeferred = CompletableDeferred<List<String>>()
        val existing = inFlight.putIfAbsent(sessionKey, newDeferred)
        if (existing != null) {
            return existing.await()
        }

        return try {
            val resolved =
                withContext(Dispatchers.IO) {
                    val ytResult =
                        fetchYouTubeSongwriters(videoId)
                            .onFailure { e ->
                                Timber.tag("Songwriters").d(e, "YouTube MPTC songwriters lookup failed for $videoId")
                            }.getOrDefault(emptyList())
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .distinct()
                    if (ytResult.isNotEmpty()) {
                        ytResult
                    } else if (metadata != null && isBetterLyricsEnabled()) {
                        runCatching { fetchBetterLyricsSongwriters(metadata) }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                Timber.tag("Songwriters").d(e, "BetterLyrics songwriters fallback failed for $videoId")
                            }.getOrDefault(emptyList())
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .distinct()
                    } else {
                        emptyList()
                    }
                }
            if (resolved.isNotEmpty()) {
                ProviderSongwritersCache.put(context, videoId, resolved)
            }
            synchronized(cacheLock) {
                sessionCache.put(sessionKey, resolved)
            }
            newDeferred.complete(resolved)
            resolved
        } catch (e: CancellationException) {
            newDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Timber.tag("Songwriters").w(e, "Unexpected error loading songwriters for $videoId")
            newDeferred.complete(emptyList())
            emptyList()
        } finally {
            inFlight.remove(sessionKey, newDeferred)
        }
    }
}

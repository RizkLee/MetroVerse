package com.metrolist.music.cache

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.intPreferencesKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.lyrics.LyricsTranslationHelper
import com.metrolist.music.podcast.transcript.TranscriptRepository
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

val MaxTextCacheSizeKey = intPreferencesKey("maxTextCacheSize")
const val DEFAULT_TEXT_CACHE_MIB = 64
val TEXT_CACHE_LIMITS_MIB = listOf(16, 32, 64, 128, 256)

data class TextCacheState(
    val podcastBytes: Long = 0,
    val lyricsBytes: Long = 0,
    val protectedBytes: Long = 0,
    val limitMiB: Int = DEFAULT_TEXT_CACHE_MIB,
    val loading: Boolean = true,
) {
    val totalBytes: Long get() = podcastBytes + lyricsBytes
}

@OptIn(FlowPreview::class)
@Singleton
class TextCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val transcripts: TranscriptRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(TextCacheState())
    val state = _state.asStateFlow()
    private val accessFile = AtomicFile(File(context.filesDir, "text-cache/access.json"))
    private var accesses: MutableMap<String, Long>? = null
    private var indexDirty = false
    private val originFile = AtomicFile(File(context.filesDir, "text-cache/automatic.json"))
    private var origins: MutableMap<String, String>? = null
    private var originsDirty = false

    init {
        scope.launch(Dispatchers.IO) {
            combine(
                database.observeLyricsCacheCount(),
                context.dataStore.data.map { it[MaxTextCacheSizeKey] ?: DEFAULT_TEXT_CACHE_MIB }.distinctUntilChanged(),
                TextCacheSignals.changes,
            ) { _, limit, _ -> limit }
                .debounce(750)
                .collect { limit ->
                    try {
                        mutex.withLock { maintain(limit) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "Text cache maintenance failed")
                        _state.value = _state.value.copy(loading = false)
                    }
                }
        }
    }

    fun recordUse(mediaId: String?) {
        if (mediaId == null) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val index = accessIndex()
                val now = System.currentTimeMillis()
                if (now - (index[mediaId] ?: 0L) >= 60_000L) {
                    index[mediaId] = now
                    indexDirty = true
                    saveIndex()
                }
            }
        }
    }

    fun recordAutomaticallyFetched(mediaId: String, lyrics: String) {
        scope.launch(Dispatchers.IO) {
            val fingerprint = lyricsCacheFingerprint(lyrics)
            mutex.withLock {
                automaticOrigins()[mediaId] = fingerprint
                originsDirty = true
                saveOrigins()
            }
            TextCacheSignals.notifyChanged()
        }
    }

    suspend fun setLimit(limitMiB: Int): Boolean {
        require(limitMiB in TEXT_CACHE_LIMITS_MIB)
        val saved = context.safeDataStoreEdit { it[MaxTextCacheSizeKey] = limitMiB }
        if (saved) refresh()
        return saved
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val limit = context.dataStore.data.first()[MaxTextCacheSizeKey] ?: DEFAULT_TEXT_CACHE_MIB
        mutex.withLock { maintain(limit) }
    }

    suspend fun clear(target: TextCacheClearTarget, includeUnverified: Boolean = false): Boolean = scope.async(Dispatchers.IO) {
        mutex.withLock {
            val entries = entries()
            val selected = textCacheRemovalOrder(entries.map {
                if (includeUnverified) it.copy(unverified = false) else it
            }).filter {
                target == TextCacheClearTarget.ALL ||
                    (target == TextCacheClearTarget.PODCASTS && it.category == TextCacheCategory.PODCAST) ||
                    (target == TextCacheClearTarget.LYRICS && it.category == TextCacheCategory.LYRICS)
            }
            var success = true
            for (entry in selected) {
                if (remove(entry, includeUnverified) == null) success = false
            }
            if (target != TextCacheClearTarget.PODCASTS) LyricsTranslationHelper.clearMemoryCache()
            saveIndex()
            saveOrigins()
            publish(entries(), currentLimit())
            success
        }
    }.await()

    private suspend fun currentLimit(): Int =
        context.dataStore.data.first()[MaxTextCacheSizeKey] ?: DEFAULT_TEXT_CACHE_MIB

    private suspend fun maintain(limitMiB: Int) {
        val limit = limitMiB.coerceAtLeast(0)
        val before = entries()
        var remaining = before.sumOf { it.bytes }
        var attempted = false
        var removedLyrics = false
        for (entry in textCacheRemovalOrder(before)) {
            if (remaining <= limit * 1024L * 1024L) break
            attempted = true
            val removed = remove(entry) ?: 0L
            remaining -= removed
            if (entry.category == TextCacheCategory.LYRICS && removed > 0) removedLyrics = true
        }
        if (removedLyrics) LyricsTranslationHelper.clearMemoryCache()
        val after = if (attempted) entries() else before
        val lyricIds = after.filter { it.category == TextCacheCategory.LYRICS }.mapTo(mutableSetOf()) { it.id }
        if (accessIndex().keys.retainAll(lyricIds)) indexDirty = true
        if (automaticOrigins().keys.retainAll(lyricIds)) originsDirty = true
        saveIndex()
        saveOrigins()
        publish(after, limit)
    }

    private suspend fun entries(): List<TextCacheEntry> {
        val index = accessIndex()
        val now = System.currentTimeMillis()
        val podcastEntries = transcripts.cachedTextEntries().map {
            TextCacheEntry(
                id = it.key,
                category = TextCacheCategory.PODCAST,
                bytes = it.bytes,
                lastAccessMs = it.lastAccessMs,
                protected = TextCacheSignals.isProtected(it.key),
            )
        }
        val lyricEntries = database.lyricsCacheEntries().map {
            val lastAccess = index.getOrPut(it.id) {
                indexDirty = true
                it.lastPlayedMs.takeIf { time -> time > 0 } ?: now
            }
            TextCacheEntry(
                id = it.id,
                category = TextCacheCategory.LYRICS,
                bytes = it.bytes,
                lastAccessMs = lastAccess,
                priority = lyricCachePriority(it.hasSong, it.inLibrary, it.liked, it.downloaded),
                protected = it.provider == "Manual" || TextCacheSignals.isProtected(it.id),
                unverified = it.id !in automaticOrigins(),
            )
        }
        return podcastEntries + lyricEntries
    }

    private suspend fun remove(entry: TextCacheEntry, includeUnverified: Boolean = false): Long? {
        if (TextCacheSignals.isProtected(entry.id)) return 0L
        return when (entry.category) {
            TextCacheCategory.PODCAST -> {
                if (transcripts.removeCachedText(entry.id)) entry.bytes
                else if (TextCacheSignals.isProtected(entry.id)) 0L else null
            }
            TextCacheCategory.LYRICS -> {
                val current = database.lyrics(entry.id).first() ?: return 0L
                if (current.provider == "Manual") return 0L
                // Older editors kept the provider name. Only a verified original can be auto-evicted.
                if (!includeUnverified && !hasVerifiedCacheOrigin(current.lyrics, automaticOrigins()[entry.id])) {
                    if (automaticOrigins().remove(entry.id) != null) originsDirty = true
                    return 0L
                }
                if (TextCacheSignals.isProtected(entry.id)) return 0L
                // Recheck the source in SQL so a concurrent edit cannot be removed by an old scan.
                val deleted = database.deleteCachedLyricsIfUnchanged(entry.id, current.lyrics)
                if (accessIndex().remove(entry.id) != null) indexDirty = true
                if (automaticOrigins().remove(entry.id) != null) originsDirty = true
                if (deleted > 0) entry.bytes else 0L
            }
        }
    }

    private fun publish(entries: List<TextCacheEntry>, limit: Int) {
        _state.value = TextCacheState(
            podcastBytes = entries.filter { it.category == TextCacheCategory.PODCAST }.sumOf { it.bytes },
            lyricsBytes = entries.filter { it.category == TextCacheCategory.LYRICS }.sumOf { it.bytes },
            protectedBytes = entries.filter { it.protected || it.unverified }.sumOf { it.bytes },
            limitMiB = limit,
            loading = false,
        )
    }

    private fun accessIndex(): MutableMap<String, Long> = accesses ?: run {
        val loaded = try {
            Json.decodeFromString<Map<String, Long>>(accessFile.openRead().bufferedReader().use { it.readText() }).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
        loaded.also { accesses = it }
    }

    private fun saveIndex() {
        if (!indexDirty) return
        val index = accesses ?: return
        var output: java.io.FileOutputStream? = null
        try {
            accessFile.baseFile.parentFile?.mkdirs()
            output = accessFile.startWrite()
            output.write(Json.encodeToString(index).toByteArray(Charsets.UTF_8))
            accessFile.finishWrite(output)
            indexDirty = false
        } catch (error: Exception) {
            accessFile.failWrite(output)
            Timber.w(error, "Could not save text cache access times")
        }
    }

    private fun automaticOrigins(): MutableMap<String, String> = origins ?: run {
        val loaded = try {
            Json.decodeFromString<Map<String, String>>(originFile.openRead().bufferedReader().use { it.readText() }).toMutableMap()
        } catch (_: Exception) {
            // Missing or damaged ownership data must not turn user-authored lyrics into cache.
            mutableMapOf()
        }
        loaded.also { origins = it }
    }

    private fun saveOrigins() {
        if (!originsDirty) return
        var output: java.io.FileOutputStream? = null
        try {
            originFile.baseFile.parentFile?.mkdirs()
            output = originFile.startWrite()
            output.write(Json.encodeToString(automaticOrigins()).toByteArray(Charsets.UTF_8))
            originFile.finishWrite(output)
            originsDirty = false
        } catch (error: Exception) {
            originFile.failWrite(output)
            Timber.w(error, "Could not save lyric cache ownership")
        }
    }
}

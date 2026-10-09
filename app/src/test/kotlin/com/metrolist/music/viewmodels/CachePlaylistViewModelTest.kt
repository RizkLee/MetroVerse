/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * MetroVerse modifications (C) 2026 Rizklee
 */

package com.metrolist.music.viewmodels

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.util.TreeSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class CachePlaylistViewModelTest {
    @Test
    fun `downloaded songs keep their date even without format or cached bytes`() {
        val player = CachePlaylistTestCache()
        val download = CachePlaylistTestCache()
        val downloaded = song("downloaded", contentLength = null, downloaded = true)

        val result = partitionCachedSongs(listOf(downloaded), player.cache, download.cache)

        assertEquals(listOf(downloaded), result.stillCached)
        assertTrue(result.stale.isEmpty())
        assertTrue(player.cacheChecks.isEmpty())
        assertTrue(download.cacheChecks.isEmpty())
    }

    @Test
    fun `either cache must contain the complete known length and unknown lengths stay stale`() {
        val player = CachePlaylistTestCache().apply {
            setCachedLength("player", 128)
            setCachedLength("partial", 64)
            setCachedLength("unknown", 128)
        }
        val download = CachePlaylistTestCache().apply {
            setCachedLength("download", 128)
            setCachedLength("partial", 64)
            setCachedLength("unknown", 128)
        }
        val flagged = listOf(
            song("player"),
            song("download"),
            song("partial"),
            song("missing"),
            song("unknown", contentLength = null),
        )

        val result = partitionCachedSongs(flagged, player.cache, download.cache)

        assertEquals(listOf("player", "download"), result.stillCached.map { it.id })
        assertEquals(listOf("partial", "missing", "unknown"), result.stale.map { it.id })
        val checks = player.cacheChecks + download.cacheChecks
        assertTrue(checks.all { (_, position, length) -> position == 0L && length == 128L })
        assertTrue(checks.none { it.first == "unknown" })
        assertTrue(player.cacheChecks.none { it.first == "download" })
    }

    @Test
    fun `initial signal follows registration and both caches report changes without touch noise`() = runBlocking {
        val player = CachePlaylistTestCache()
        val download = CachePlaylistTestCache()
        val ids = linkedSetOf("first", "second")
        val candidates = MutableSharedFlow<Set<String>>(replay = 1).apply { tryEmit(ids) }
        val events = Channel<Set<String>>(Channel.UNLIMITED)
        download.onAddListener = { key ->
            if (key == "first") player.addSpan("first", 128)
        }
        val observer = launch(Dispatchers.Unconfined) {
            observeCachePlaylistChanges(candidates, player.cache, download.cache).collect {
                assertEquals(ids, player.listenerKeys)
                assertEquals(ids, download.listenerKeys)
                events.send(it)
            }
        }

        try {
            assertEquals(ids, events.next())
            assertTrue(events.tryReceive().isFailure)
            player.addSpan("first", 256)
            assertEquals(ids, events.next())
            download.addSpan("second", 128)
            assertEquals(ids, events.next())
            player.removeSpan("first")
            assertEquals(ids, events.next())
            download.removeSpan("second")
            assertEquals(ids, events.next())

            player.touchSpan("first")
            download.touchSpan("second")
            player.addSpan("unmarked", 128)
            candidates.emit(linkedSetOf("second", "first"))
            yield()

            assertTrue(events.tryReceive().isFailure)
            assertEquals(listOf("first", "second"), player.registrationCalls)
            assertEquals(listOf("first", "second"), download.registrationCalls)
            assertTrue(player.cacheChecks.isEmpty())
            assertTrue(download.cacheChecks.isEmpty())
        } finally {
            observer.cancelAndJoin()
        }
        assertTrue(player.listenerKeys.isEmpty())
        assertTrue(download.listenerKeys.isEmpty())
        assertEquals(player.registrationCalls, player.removalCalls)
        assertEquals(download.registrationCalls, download.removalCalls)
    }

    @Test
    fun `candidate changes replace registrations and empty candidates release all listeners`() = runBlocking {
        val player = CachePlaylistTestCache()
        val download = CachePlaylistTestCache()
        val candidates = MutableStateFlow(setOf("old"))
        val events = Channel<Set<String>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Unconfined) {
            observeCachePlaylistChanges(candidates, player.cache, download.cache).collect(events::send)
        }

        try {
            assertEquals(setOf("old"), events.next())
            candidates.value = setOf("new")
            assertEquals(setOf("new"), events.next())
            assertEquals(setOf("new"), player.listenerKeys)
            assertEquals(setOf("new"), download.listenerKeys)
            assertEquals(listOf("old"), player.removalCalls)
            assertEquals(listOf("old"), download.removalCalls)

            player.removeSpan("old")
            candidates.value = emptySet()
            assertEquals(emptySet<String>(), events.next())
            assertTrue(events.tryReceive().isFailure)
            assertTrue(player.listenerKeys.isEmpty())
            assertTrue(download.listenerKeys.isEmpty())
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `partial registration failure unregisters every attempt and emits one fallback without retrying`() = runBlocking {
        val player = CachePlaylistTestCache()
        val download = CachePlaylistTestCache().apply { failOnAddKey = "second" }
        val ids = linkedSetOf("first", "second")

        val events = withTimeout(5_000) {
            observeCachePlaylistChanges(flowOf(ids), player.cache, download.cache).toList()
        }

        assertEquals(listOf(ids), events)
        assertEquals(listOf("first", "second"), player.registrationCalls)
        assertEquals(listOf("first", "second"), download.registrationCalls)
        assertEquals(player.registrationCalls, player.removalCalls)
        assertEquals(download.registrationCalls, download.removalCalls)
        assertTrue(player.listenerKeys.isEmpty())
        assertTrue(download.listenerKeys.isEmpty())
    }

    private suspend fun <T> Channel<T>.next(): T = withTimeout(5_000) { receive() }

    private fun song(id: String, contentLength: Long? = 128, downloaded: Boolean = false): Song = Song(
        song = SongEntity(
            id = id,
            title = id,
            dateDownload = LocalDateTime.of(2026, 10, 1, 12, 0),
            isDownloaded = downloaded,
        ),
        artists = emptyList(),
        format = contentLength?.let { cachePlaylistTestFormat(id, it) },
    )
}

@UnstableApi
internal class CachePlaylistTestCache {
    private val listeners = linkedMapOf<String, MutableList<Cache.Listener>>()
    private val cachedLengths = mutableMapOf<String, Long>()
    val registrationCalls = mutableListOf<String>()
    val removalCalls = mutableListOf<String>()
    val cacheChecks = mutableListOf<Triple<String, Long, Long>>()
    var failOnAddKey: String? = null
    var onAddListener: ((String) -> Unit)? = null

    val listenerKeys: Set<String>
        get() = listeners.keys.toSet()

    val cache: Cache = Proxy.newProxyInstance(
        Cache::class.java.classLoader,
        arrayOf(Cache::class.java),
    ) { proxy, method, args ->
        val arguments = args.orEmpty()
        when (method.name) {
            "addListener" -> {
                val key = arguments[0] as String
                registrationCalls += key
                listeners.getOrPut(key) { mutableListOf() } += arguments[1] as Cache.Listener
                onAddListener?.invoke(key)
                if (key == failOnAddKey) throw IllegalStateException("Cache unavailable")
                TreeSet<CacheSpan>()
            }
            "removeListener" -> {
                val key = arguments[0] as String
                removalCalls += key
                listeners[key]?.let {
                    it.remove(arguments[1] as Cache.Listener)
                    if (it.isEmpty()) listeners.remove(key)
                }
                null
            }
            "isCached" -> {
                val key = arguments[0] as String
                val position = arguments[1] as Long
                val length = arguments[2] as Long
                cacheChecks += Triple(key, position, length)
                cachedLengths[key]?.let { it >= position + length } ?: false
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments[0]
            "toString" -> "CachePlaylistTestCache"
            else -> error("Unexpected Cache call: ${method.name}")
        }
    } as Cache

    fun setCachedLength(key: String, length: Long) {
        cachedLengths[key] = length
    }

    fun addSpan(key: String, length: Long) {
        cachedLengths[key] = length
        val span = CacheSpan(key, 0, length)
        listeners[key]?.toList()?.forEach { it.onSpanAdded(cache, span) }
    }

    fun removeSpan(key: String) {
        val span = CacheSpan(key, 0, cachedLengths.remove(key) ?: 128L)
        listeners[key]?.toList()?.forEach { it.onSpanRemoved(cache, span) }
    }

    fun touchSpan(key: String) {
        val span = CacheSpan(key, 0, cachedLengths[key] ?: 128L)
        listeners[key]?.toList()?.forEach { it.onSpanTouched(cache, span, span) }
    }
}

internal fun cachePlaylistTestFormat(id: String, contentLength: Long): FormatEntity = FormatEntity(
    id = id,
    itag = 251,
    mimeType = "audio/webm",
    codecs = "opus",
    bitrate = 128_000,
    sampleRate = 48_000,
    contentLength = contentLength,
    loudnessDb = null,
    playbackUrl = null,
)

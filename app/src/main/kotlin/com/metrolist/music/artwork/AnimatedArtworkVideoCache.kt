/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.artwork

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.music.constants.MaxAnimatedArtworkCacheSizeKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class AnimatedArtworkVideoCache @Inject constructor(
    @ApplicationContext context: Context,
    private val databaseProvider: DatabaseProvider,
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.cacheDir, CACHE_DIR_NAME)
    private val lock = Any()

    @Volatile
    private var evictor: ResizableEvictor? = null

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var configuredLimitMb: Int? = null

    fun streamingFactory(upstream: DataSource.Factory): DataSource.Factory {
        val limitMb = resolveLimitMb()
        if (limitMb <= 0) return upstream
        return CacheDataSource
            .Factory()
            .setCache(getOrCreateCache(limitMb))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun setLimitMb(limitMb: Int) {
        synchronized(lock) {
            configuredLimitMb = limitMb
            val existing = cache
            if (existing != null) {
                evictor?.resize(existing, limitMb.coerceAtLeast(0).toLong() * BYTES_PER_MIB)
            } else if (limitMb <= 0 && directory.exists()) {
                SimpleCache.delete(directory, databaseProvider)
            }
        }
    }

    /** Callers must invoke on Dispatchers.IO. */
    fun sizeBytes(): Long {
        val existing = synchronized(lock) { cache }
        if (existing != null) return existing.cacheSpace
        if (!directory.exists()) return 0L
        return directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Callers must invoke on Dispatchers.IO. */
    fun clear() {
        synchronized(lock) {
            val existing = cache
            if (existing != null) {
                existing.keys.toList().forEach(existing::removeResource)
            } else if (directory.exists()) {
                SimpleCache.delete(directory, databaseProvider)
            }
        }
    }

    private fun resolveLimitMb(): Int =
        configuredLimitMb ?: (appContext.dataStore[MaxAnimatedArtworkCacheSizeKey] ?: DEFAULT_CACHE_LIMIT_MIB).also {
            configuredLimitMb = it
        }

    private fun getOrCreateCache(limitMb: Int): SimpleCache =
        cache ?: synchronized(lock) {
            cache ?: run {
                val createdEvictor = ResizableEvictor(limitMb.coerceAtLeast(0).toLong() * BYTES_PER_MIB)
                evictor = createdEvictor
                SimpleCache(directory, createdEvictor, databaseProvider).also { cache = it }
            }
        }

    private class ResizableEvictor(
        var limit: Long,
    ) : CacheEvictor {
        private var delegate = LeastRecentlyUsedCacheEvictor(limit)

        fun resize(
            cache: Cache,
            bytes: Long,
        ) = synchronized(cache) {
            if (limit == bytes) return@synchronized
            limit = bytes
            delegate = LeastRecentlyUsedCacheEvictor(bytes)
            cache.keys
                .flatMap { cache.getCachedSpans(it) }
                .sortedBy { it.lastTouchTimestamp }
                .forEach { span ->
                    if (span in cache.getCachedSpans(span.key)) {
                        delegate.onSpanAdded(cache, span)
                    }
                }
        }

        override fun requiresCacheSpanTouches() = true

        override fun onCacheInitialized() = delegate.onCacheInitialized()

        override fun onStartFile(
            cache: Cache,
            key: String,
            position: Long,
            length: Long,
        ) = delegate.onStartFile(cache, key, position, length)

        override fun onSpanAdded(
            cache: Cache,
            span: CacheSpan,
        ) = delegate.onSpanAdded(cache, span)

        override fun onSpanRemoved(
            cache: Cache,
            span: CacheSpan,
        ) = delegate.onSpanRemoved(cache, span)

        override fun onSpanTouched(
            cache: Cache,
            oldSpan: CacheSpan,
            newSpan: CacheSpan,
        ) = delegate.onSpanTouched(cache, oldSpan, newSpan)
    }

    private companion object {
        const val CACHE_DIR_NAME = "animated_artwork_video"
        const val DEFAULT_CACHE_LIMIT_MIB = 512
        const val BYTES_PER_MIB = 1024L * 1024L
    }
}

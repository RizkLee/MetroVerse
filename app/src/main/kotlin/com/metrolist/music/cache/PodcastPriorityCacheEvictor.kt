/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * MetroVerse modifications (C) 2026 Rizklee: prioritize podcast spans while
 * retaining Media3's LRU touch and removal lifecycle.
 */
package com.metrolist.music.cache

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/** Used only by the streaming cache; the separate download cache never uses this evictor. */
internal class PodcastPriorityCacheEvictor(
    private val maxBytes: Long,
    private val isPodcast: (String) -> Boolean,
    private val isCurrent: (String) -> Boolean = { false },
) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { left, right ->
        val time = left.lastTouchTimestamp.compareTo(right.lastTouchTimestamp)
        if (time != 0) time else left.compareTo(right)
    }
    private var currentBytes = 0L
    private var initialized = false
    private var initializingCache: Cache? = null

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {
        initialized = true
        initializingCache?.let { evict(it, 0) }
        initializingCache = null
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evict(cache, length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (spans.add(span)) currentBytes += span.length
        // Directory scan order is arbitrary; wait until all old podcast spans are known.
        if (initialized) evict(cache, 0) else initializingCache = cache
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) currentBytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, requiredBytes: Long) {
        while (currentBytes + requiredBytes > maxBytes && spans.isNotEmpty()) {
            val candidate = spans.firstOrNull { isPodcast(it.key) && !isCurrent(it.key) }
                ?: spans.firstOrNull { !isPodcast(it.key) && !isCurrent(it.key) }
                ?: return
            cache.removeSpan(candidate)
        }
    }
}

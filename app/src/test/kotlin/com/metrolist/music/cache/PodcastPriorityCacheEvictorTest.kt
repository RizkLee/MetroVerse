package com.metrolist.music.cache

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class PodcastPriorityCacheEvictorTest {
    @Test fun `a recent podcast is evicted before older music`() {
        val harness = Harness()
        harness.add("music", 60, 1)
        harness.add("rss_episode_recent", 40, 20)
        harness.add("new-music", 30, 30)
        assertEquals(listOf("rss_episode_recent"), harness.removed)
        assertEquals(setOf("music", "new-music"), harness.keys())
    }

    @Test fun `an incoming podcast can evict idle music when reserving space`() {
        val harness = Harness()
        harness.add("music", 100, 1)
        harness.evictor.onStartFile(harness.cache, "rss_episode_new", 0, 30)
        assertEquals(listOf("music"), harness.removed)
        harness.add("rss_episode_new", 30, 2)
        assertEquals(setOf("rss_episode_new"), harness.keys())
    }

    @Test fun `an incoming podcast does not evict the current song merely to reserve space`() {
        val harness = Harness(current = "music")
        harness.add("music", 100, 1)
        harness.evictor.onStartFile(harness.cache, "rss_episode_new", 0, 30)
        assertTrue(harness.removed.isEmpty())
        harness.add("rss_episode_new", 30, 2)
        assertEquals(listOf("rss_episode_new"), harness.removed)
        assertEquals(setOf("music"), harness.keys())
    }

    @Test fun `startup scans all spans before deciding what to discard`() {
        val harness = Harness(initialize = false)
        harness.add("music-old", 50, 1)
        harness.add("music-new", 50, 2)
        harness.add("rss_episode", 60, 3)
        assertTrue(harness.removed.isEmpty())
        harness.evictor.onCacheInitialized()
        assertEquals(listOf("rss_episode"), harness.removed)
        assertEquals(setOf("music-old", "music-new"), harness.keys())
    }

    @Test fun `idle podcasts are evicted before idle songs while the current podcast is retained`() {
        val harness = Harness(current = "rss_episode_active")
        harness.add("rss_episode_active", 50, 1)
        harness.add("rss_episode_idle", 50, 2)
        harness.add("music", 50, 3)
        assertEquals(listOf("rss_episode_idle"), harness.removed)
        harness.add("more-music", 50, 4)
        assertEquals(listOf("rss_episode_idle", "music"), harness.removed)
        assertEquals(setOf("rss_episode_active", "more-music"), harness.keys())
        assertEquals(100L, harness.spans.sumOf { it.length })
    }

    @Test fun `current podcast spans are retained when the soft limit cannot be met`() {
        val harness = Harness(limit = 100, current = "rss_episode_active")
        harness.add("rss_episode_active", 100, 1)
        harness.add("rss_episode_active", 20, 2, position = 100)
        assertTrue(harness.removed.isEmpty())
        assertEquals(120L, harness.spans.sumOf { it.length })
    }

    @Test fun `current song spans are retained when the soft limit cannot be met`() {
        val harness = Harness(limit = 100, current = "music-active")
        harness.add("music-active", 100, 1)
        harness.add("music-active", 20, 2, position = 100)
        assertTrue(harness.removed.isEmpty())
        assertEquals(120L, harness.spans.sumOf { it.length })
    }

    @Test fun `touch callbacks preserve LRU order and byte accounting`() {
        val harness = Harness()
        val first = harness.add("music-a", 40, 1)
        harness.add("music-b", 40, 2)
        val touched = CacheSpan(first.key, 0, first.length, 5, first.file)
        harness.spans.remove(first)
        harness.spans.add(touched)
        harness.evictor.onSpanTouched(harness.cache, first, touched)
        harness.add("music-c", 40, 6)
        assertEquals(listOf("music-b"), harness.removed)
        assertEquals(80L, harness.spans.sumOf { it.length })
    }

    @Test fun `youtube podcast IDs use episode metadata and legacy keys stay unchanged`() {
        val policy = AudioCachePolicy()
        policy.registerPodcasts(listOf("youtube-podcast"))
        policy.onPlaybackChanged("new-podcast", true)
        assertTrue(policy.isPodcast("youtube-podcast"))
        assertTrue(policy.isPodcast("new-podcast"))
        assertTrue(policy.isPodcast("rss_episode_legacy"))
        assertFalse(policy.isPodcast("ordinary-music"))
        assertTrue(policy.isCurrent("new-podcast"))
        policy.onPlaybackChanged(null, false)
        assertFalse(policy.isCurrent("new-podcast"))
        val harness = Harness(podcast = policy::isPodcast)
        harness.add("old-music", 60, 1)
        harness.add("youtube-podcast", 40, 2)
        harness.add("new-music", 30, 3)
        assertEquals(listOf("youtube-podcast"), harness.removed)
    }

    @Test fun `unknown writer length waits for actual committed bytes`() {
        val harness = Harness()
        harness.add("music", 100, 1)
        harness.evictor.onStartFile(harness.cache, "another", 0, C.LENGTH_UNSET.toLong())
        assertTrue(harness.removed.isEmpty())
        harness.add("rss_episode", 20, 2)
        assertEquals(listOf("rss_episode"), harness.removed)
    }

    @Test fun `zero budget removes committed data and empty startup is valid`() {
        val harness = Harness(limit = 0)
        harness.add("rss_episode", 10, 1)
        harness.add("music", 10, 2)
        assertTrue(harness.spans.isEmpty())
    }

    private class Harness(
        limit: Long = 100,
        current: String? = null,
        initialize: Boolean = true,
        podcast: (String) -> Boolean = { it.startsWith("rss_episode") },
    ) {
        val spans = mutableListOf<CacheSpan>()
        val removed = mutableListOf<String>()
        val evictor = PodcastPriorityCacheEvictor(limit, podcast, { it == current })
        val cache: Cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            when (method.name) {
                "removeSpan" -> {
                    val span = args!![0] as CacheSpan
                    check(spans.remove(span))
                    removed += span.key
                    evictor.onSpanRemoved(cacheReference(), span)
                    null
                }
                else -> error("Unexpected Cache method: ${method.name}")
            }
        } as Cache
        init { if (initialize) evictor.onCacheInitialized() }
        private fun cacheReference(): Cache = cache
        fun add(key: String, bytes: Long, touched: Long, position: Long = 0): CacheSpan = CacheSpan(key, position, bytes, touched, File("unused-$key")).also {
            spans.add(it)
            evictor.onSpanAdded(cache, it)
        }
        fun keys() = spans.map { it.key }.toSet()
    }
}

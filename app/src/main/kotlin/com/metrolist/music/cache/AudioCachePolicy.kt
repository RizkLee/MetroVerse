/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.cache

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Cache callbacks only read memory: no Room queries or player access under the cache lock. */
@Singleton
class AudioCachePolicy @Inject constructor() {
    private val podcastIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var currentMediaId: String? = null

    fun registerPodcasts(ids: Collection<String>) { podcastIds.addAll(ids) }

    fun onPlaybackChanged(mediaId: String?, isEpisode: Boolean) {
        if (mediaId != null && isEpisode) podcastIds.add(mediaId)
        currentMediaId = mediaId
    }

    fun isPodcast(key: String): Boolean = key.startsWith("rss_episode_") || key in podcastIds
    fun isCurrent(key: String): Boolean = key == currentMediaId
}

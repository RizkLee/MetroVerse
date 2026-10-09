/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class PodcastSubtitleOffsetStore(context: Context) {
    private val appContext = context.applicationContext

    fun offsetMs(episodeId: String): Flow<Int> {
        val key = keyForEpisode(episodeId)
        return appContext.dataStore.data
            .map { preferences -> SubtitleTimingOffset.clamp(preferences[key] ?: 0) }
            .distinctUntilChanged()
    }

    suspend fun setOffsetMs(episodeId: String, offsetMs: Int): Boolean {
        val key = keyForEpisode(episodeId)
        val value = SubtitleTimingOffset.clamp(offsetMs)
        return appContext.safeDataStoreEdit { preferences ->
            if (value == 0) preferences.remove(key) else preferences[key] = value
        }
    }

    companion object {
        internal fun keyForEpisode(episodeId: String): Preferences.Key<Int> {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(episodeId.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return intPreferencesKey("podcast_subtitle_offset_$digest")
        }
    }
}

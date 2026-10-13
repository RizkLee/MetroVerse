/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.first

/** Existing preferences hold language overrides; Room and episode media identity stay unchanged. */
internal class AsrLanguagePreferences(private val context: Context) {
    private fun episodeKey(id: String) = stringPreferencesKey("podcastAsrEpisodeLanguage_" + subtitleCacheFingerprint(id))
    private fun channelKey(id: String) = stringPreferencesKey("podcastAsrChannelLanguage_" + subtitleCacheFingerprint(id))

    suspend fun resolve(episodeId: String, channelId: String?, channelName: String, rssLanguage: String?): AsrLanguageSelection {
        val preferences = context.dataStore.data.first()
        val channelDefault = channelId?.let { preferences[channelKey(it)] }
        return AsrLanguageSelection(
            resolveAsrLanguage(preferences[episodeKey(episodeId)], channelDefault, rssLanguage),
            channelName, channelId, channelDefault,
        )
    }

    suspend fun save(episodeId: String, channelId: String?, language: AsrLanguage, useForChannel: Boolean): Boolean =
        context.safeDataStoreEdit { preferences ->
            preferences[episodeKey(episodeId)] = language.code
            channelId?.let {
                if (useForChannel) preferences[channelKey(it)] = language.code
                else preferences.remove(channelKey(it))
            }
        }
}

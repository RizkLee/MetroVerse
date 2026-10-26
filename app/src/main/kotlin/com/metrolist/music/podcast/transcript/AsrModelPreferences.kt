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

internal class AsrModelPreferences(private val context: Context) {
    private fun key(language: AsrLanguage) = stringPreferencesKey("podcastAsrModel_" + language.code)

    suspend fun load(readyModels: Set<AsrModel>): Map<AsrLanguage, AsrModelVariant> {
        val preferences = context.dataStore.data.first()
        return AsrLanguage.entries.associateWith { language ->
            preferredAsrVariant(language, preferences[key(language)], readyModels)
        }
    }

    suspend fun save(model: AsrModel): Boolean = context.safeDataStoreEdit {
        it[key(model.language)] = model.variant.pathName
    }
}

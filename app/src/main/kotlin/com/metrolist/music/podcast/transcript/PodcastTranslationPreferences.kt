/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import androidx.datastore.preferences.core.MutablePreferences
import com.metrolist.music.constants.PodcastTranslateLanguageKey
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.constants.SYSTEM_DEFAULT
import com.metrolist.music.ui.utils.effectiveAppLanguageCode
import java.util.Locale

internal fun podcastTranslationDefaultLanguage(savedAppLanguage: String?, resourceLocale: Locale): String {
    val locale = savedAppLanguage?.takeIf { it.isNotBlank() && it != SYSTEM_DEFAULT }
        ?.let { Locale.forLanguageTag(it.replace('_', '-')) } ?: resourceLocale
    return effectiveAppLanguageCode(locale)
}

/** Copy the former shared target once, so later lyrics changes cannot change podcast translation. */
internal fun initializePodcastTranslationTarget(preferences: MutablePreferences, fallback: String) {
    if (preferences[PodcastTranslateLanguageKey] == null) {
        preferences[PodcastTranslateLanguageKey] = preferences[TranslateLanguageKey]?.takeIf { it.isNotBlank() } ?: fallback
    }
}

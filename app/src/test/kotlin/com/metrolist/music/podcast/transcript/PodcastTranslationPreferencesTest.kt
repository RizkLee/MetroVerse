/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.PodcastTranslateLanguageKey
import com.metrolist.music.constants.TranslateLanguageKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import com.metrolist.music.constants.SYSTEM_DEFAULT

class PodcastTranslationPreferencesTest {
    @Test fun `shared saved target is preserved once and later lyrics changes do not change it`() {
        val preferences = mutablePreferencesOf(TranslateLanguageKey to "ja")
        initializePodcastTranslationTarget(preferences, "en")
        assertEquals("ja", preferences[PodcastTranslateLanguageKey])
        preferences[TranslateLanguageKey] = "es"
        initializePodcastTranslationTarget(preferences, "de")
        assertEquals("ja", preferences[PodcastTranslateLanguageKey])
        assertEquals("es", preferences[TranslateLanguageKey])
    }

    @Test fun `new Chinese targets retain regional scripts instead of becoming English`() {
        assertEquals("zh-CN", podcastTranslationDefaultLanguage(null, Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-TW", podcastTranslationDefaultLanguage(SYSTEM_DEFAULT, Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("zh-HK", podcastTranslationDefaultLanguage(null, Locale.forLanguageTag("zh-HK")))
    }

    @Test fun `explicit app language overrides the device language before activity startup`() {
        assertEquals("zh-TW", podcastTranslationDefaultLanguage("zh-TW", Locale.US))
        assertEquals("de", podcastTranslationDefaultLanguage("de", Locale.CHINA))
    }

    @Test fun `existing podcast target is never overwritten and missing target uses app language`() {
        val saved = mutablePreferencesOf(PodcastTranslateLanguageKey to "de", TranslateLanguageKey to "es")
        initializePodcastTranslationTarget(saved, "en")
        assertEquals("de", saved[PodcastTranslateLanguageKey])
        val fresh = mutablePreferencesOf()
        initializePodcastTranslationTarget(fresh, "zh")
        assertEquals("zh", fresh[PodcastTranslateLanguageKey])
    }
}

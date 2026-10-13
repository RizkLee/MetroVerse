/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import org.junit.Assert.*
import org.junit.Test

class EpisodeTranslationPreferenceTest {
    @Test fun `menu enablement never carries to the next or returning episode`() {
        val preference = EpisodeTranslationPreference()
        preference.onEpisodeChanged("first")
        preference.setEnabled(true)
        preference.onEpisodeChanged("first")
        assertEquals(true, preference.override.value)
        preference.onEpisodeChanged("second")
        assertNull(preference.override.value)
        preference.onEpisodeChanged("first")
        assertNull(preference.override.value)
    }

    @Test fun `always translate defaults survive a menu disable but only for later episodes`() {
        val preference = EpisodeTranslationPreference()
        preference.onEpisodeChanged("first")
        preference.setEnabled(false)
        assertFalse(preference.override.value ?: true)
        preference.onEpisodeChanged("second")
        assertTrue(preference.override.value ?: true)
        assertFalse(preference.override.value ?: false)
    }

    @Test fun `leaving podcast playback clears the temporary selection and idle menus cannot enable it`() {
        val preference = EpisodeTranslationPreference()
        preference.onEpisodeChanged("first")
        preference.setEnabled(true)
        preference.onEpisodeChanged(null)
        preference.setEnabled(true)
        assertNull(preference.override.value)
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.constants

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAnimationStyleMigrationTest {
    @Test
    fun `fresh install sets Apple Music as default lyrics animation style`() {
        val prefs = mutablePreferencesOf()

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.APPLE.name, prefs[LyricsAnimationStyleKey])
        assertEquals(true, prefs[LyricsAnimationStyleDefaultMigratedKey])
    }

    @Test
    fun `existing install without explicit style keeps MetroVerse default`() {
        val prefs =
            mutablePreferencesOf(
                LastSeenVersionKey to "1.3.0",
            )

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.METROVERSE.name, prefs[LyricsAnimationStyleKey])
        assertEquals(true, prefs[LyricsAnimationStyleDefaultMigratedKey])
    }

    @Test
    fun `existing install detected via onboarding or music prefs keeps MetroVerse`() {
        val prefs =
            mutablePreferencesOf(
                UsageModeOnboardingCompletedKey to true,
            )

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.METROVERSE.name, prefs[LyricsAnimationStyleKey])
        assertTrue(prefs[LyricsAnimationStyleDefaultMigratedKey] == true)
    }

    @Test
    fun `explicit existing user preference is never overwritten and migration is idempotent`() {
        val prefs =
            mutablePreferencesOf(
                LastSeenVersionKey to "1.3.0",
                LyricsAnimationStyleKey to LyricsAnimationStyle.NONE.name,
            )

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.NONE.name, prefs[LyricsAnimationStyleKey])
        assertEquals(true, prefs[LyricsAnimationStyleDefaultMigratedKey])

        // Removing key manually later must not re-run migration once flag is true
        prefs.remove(LyricsAnimationStyleKey)
        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)
        assertEquals(null, prefs[LyricsAnimationStyleKey])
    }
}

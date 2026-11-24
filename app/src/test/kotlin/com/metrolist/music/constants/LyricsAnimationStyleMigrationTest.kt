/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.constants

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAnimationStyleMigrationTest {
    @Test
    fun `fresh install sets Apple Music as default lyrics animation style`() {
        val prefs = mutablePreferencesOf()

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.APPLE.name, prefs[LyricsAnimationStyleKey])
        assertEquals(true, prefs[LyricsAnimationStyleAppleDefaultMigratedKey])
    }

    @Test
    fun `existing install without explicit style defaults to Apple Music`() {
        val prefs =
            mutablePreferencesOf(
                LastSeenVersionKey to "1.2.2",
                UsageModeOnboardingCompletedKey to true,
            )

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.APPLE.name, prefs[LyricsAnimationStyleKey])
        assertTrue(prefs[LyricsAnimationStyleAppleDefaultMigratedKey] == true)
    }

    @Test
    fun `existing install with legacy 1_2_x style upgrades to Apple Music`() {
        for (legacy in listOf("FADE", "GLOW", "SLIDE", "KARAOKE")) {
            val prefs =
                mutablePreferencesOf(
                    LastSeenVersionKey to "1.2.2",
                    LyricsAnimationStyleKey to legacy,
                )

            migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

            assertEquals("Legacy $legacy should upgrade to APPLE", LyricsAnimationStyle.APPLE.name, prefs[LyricsAnimationStyleKey])
            assertTrue(prefs[LyricsAnimationStyleAppleDefaultMigratedKey] == true)
        }
    }

    @Test
    fun `prior 1_3_0 test install auto-pinned to MetroVerse upgrades to Apple Music`() {
        val prefs =
            mutablePreferencesOf(
                LastSeenVersionKey to "1.3.0",
                LyricsAnimationStyleDefaultMigratedKey to true,
                LyricsAnimationStyleKey to LyricsAnimationStyle.METROVERSE.name,
            )

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.APPLE.name, prefs[LyricsAnimationStyleKey])
        assertFalse(prefs.contains(LyricsAnimationStyleDefaultMigratedKey))
        assertEquals(true, prefs[LyricsAnimationStyleAppleDefaultMigratedKey])
    }

    @Test
    fun `explicit NONE or post-migration user choices are preserved and migration is idempotent`() {
        val prefs =
            mutablePreferencesOf(
                LastSeenVersionKey to "1.3.0",
                LyricsAnimationStyleKey to LyricsAnimationStyle.NONE.name,
            )

        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)

        assertEquals(LyricsAnimationStyle.NONE.name, prefs[LyricsAnimationStyleKey])
        assertEquals(true, prefs[LyricsAnimationStyleAppleDefaultMigratedKey])

        // Once migrated, user's explicit selection of METROVERSE or NONE is preserved on subsequent runs
        prefs[LyricsAnimationStyleKey] = LyricsAnimationStyle.METROVERSE.name
        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)
        assertEquals(LyricsAnimationStyle.METROVERSE.name, prefs[LyricsAnimationStyleKey])

        prefs.remove(LyricsAnimationStyleKey)
        migrateLyricsAnimationStyleDefaultIfNeeded(prefs)
        assertEquals(null, prefs[LyricsAnimationStyleKey])
    }
}

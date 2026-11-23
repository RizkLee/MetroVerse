/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconTest {
    @Test
    fun `fromStoredValue resolves known icons and defaults unknown values cleanly`() {
        assertEquals(AppIcon.DEFAULT, AppIcon.fromStoredValue(null))
        assertEquals(AppIcon.DEFAULT, AppIcon.fromStoredValue(""))
        assertEquals(AppIcon.DEFAULT, AppIcon.fromStoredValue("unknown_icon"))
        assertEquals(AppIcon.DEFAULT, AppIcon.fromStoredValue("default"))
        assertEquals(AppIcon.PIANO_BLACK, AppIcon.fromStoredValue("piano_black"))
        assertEquals(AppIcon.PIANO_BLACK, AppIcon.fromStoredValue(" PIANO_BLACK "))
    }

    @Test
    fun `app icon entries have unique manifest aliases and single enabled default`() {
        assertEquals("MainActivityAlias", AppIcon.DEFAULT.aliasName)
        assertTrue(AppIcon.DEFAULT.enabledByDefault)
        assertEquals(1, AppIcon.entries.count { it.enabledByDefault })
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.aliasName }.distinct().size)
    }
}

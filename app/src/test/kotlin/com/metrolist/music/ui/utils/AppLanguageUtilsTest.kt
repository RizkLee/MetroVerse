/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLanguageUtilsTest {
    @Test
    fun `effective app language maps regional locales to supported translation codes`() {
        assertEquals("zh-TW", effectiveAppLanguageCode(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("pt", effectiveAppLanguageCode(Locale.forLanguageTag("pt-BR")))
        assertEquals("es-419", effectiveAppLanguageCode(Locale.forLanguageTag("es-US")))
        assertEquals("en-GB", effectiveAppLanguageCode(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun `unsupported app language falls back to English`() {
        assertEquals("en", effectiveAppLanguageCode(Locale.forLanguageTag("eo")))
    }

    @Test
    fun `romanization defaults disable only the current app language`() {
        val chineseDefaults = defaultRomanizationLanguageSettings("zh-CN").toMap()

        assertFalse(chineseDefaults.getValue("Chinese"))
        assertTrue(chineseDefaults.getValue("Japanese"))
        assertTrue(defaultRomanizationLanguageSettings("en").all { it.second })
    }
}

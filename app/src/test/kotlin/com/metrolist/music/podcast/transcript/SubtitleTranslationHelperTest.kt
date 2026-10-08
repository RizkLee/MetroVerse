/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationHelperTest {

    @Test
    fun `alignTranslations preserves blank cue positions`() {
        assertEquals(
            listOf("one", "", "three"),
            SubtitleTranslationHelper.alignTranslations(
                lines = listOf("uno", "", "tres"),
                translatedLines = listOf("one", "three"),
            ),
        )
    }

    @Test
    fun `alignTranslations rejects incomplete provider response`() {
        try {
            SubtitleTranslationHelper.alignTranslations(
                lines = listOf("first", "second"),
                translatedLines = listOf("only one"),
            )
            throw AssertionError("Expected an incomplete response to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank translations cannot count as successful completed work`() {
        SubtitleTranslationHelper.alignTranslations(listOf("hello", "world"), listOf("bonjour", ""))
    }
}

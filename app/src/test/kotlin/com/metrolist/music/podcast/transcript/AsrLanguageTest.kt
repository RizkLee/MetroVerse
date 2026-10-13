/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import org.junit.Assert.*
import org.junit.Test

class AsrLanguageTest {
    @Test fun `RSS regional tags map to the four streaming models`() {
        assertEquals(AsrLanguage.GERMAN, AsrLanguage.fromCode(" de-DE "))
        assertEquals(AsrLanguage.SPANISH, AsrLanguage.fromCode("es_419"))
        assertEquals(AsrLanguage.JAPANESE, AsrLanguage.fromCode("ja-JP"))
        assertEquals(AsrLanguage.ENGLISH, AsrLanguage.fromCode("EN-us"))
        assertNull(AsrLanguage.fromCode("zh-CN"))
        assertEquals("zh", resolveAsrLanguage(null, null, "zh-CN"))
    }
    @Test fun `manual episode selection wins over channel and RSS defaults`() {
        assertEquals("de", resolveAsrLanguage("de", "es", "ja"))
        assertEquals("es", resolveAsrLanguage(null, "es", "ja"))
        assertEquals("ja", resolveAsrLanguage(null, null, "ja"))
        assertEquals("en", resolveAsrLanguage(null, null, null))
    }
    @Test fun `model and checkpoint identities isolate languages and preserve existing English models`() {
        assertEquals(MoonshineModelFiles.DIRECTORY, AsrLanguage.ENGLISH.directory)
        assertEquals(4, AsrLanguage.entries.map { it.directory }.toSet().size)
        assertEquals(4, AsrLanguage.entries.map { it.checkpointIdentity }.toSet().size)
        AsrLanguage.entries.forEach { language ->
            val files = MoonshineModelFiles.artifacts(language)
            assertEquals(8, files.size)
            assertTrue(files.all { it.sha256.length == 64 && it.bytes > 0 && it.urls.single().contains("small-streaming-${language.code}/") })
        }
    }
    @Test fun `a retry cannot immediately retry again and healthy progress must exceed two minutes`() {
        val retry = AsrAutomaticRetry()
        assertTrue(retry.retry())
        assertFalse(retry.retry())
        (0L..120_000L step 5_000).forEach(retry::madeProgress)
        assertFalse(retry.retry())
        retry.madeProgress(125_000)
        assertTrue(retry.retry())
        assertFalse(retry.retry())
    }
    @Test fun `buffering time without inference progress cannot rearm a retry`() {
        val retry = AsrAutomaticRetry()
        assertTrue(retry.retry())
        retry.madeProgress(0)
        retry.madeProgress(180_000)
        assertFalse(retry.retry())
        (185_000L..305_000L step 5_000).forEach(retry::madeProgress)
        assertTrue(retry.retry())
    }
}

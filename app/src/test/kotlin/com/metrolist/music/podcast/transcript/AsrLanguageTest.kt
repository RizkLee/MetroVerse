/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import org.junit.Assert.*
import org.junit.Test

class AsrLanguageTest {
    @Test fun `RSS regional tags map to the supported streaming models`() {
        assertEquals(AsrLanguage.GERMAN, AsrLanguage.fromCode(" de-DE "))
        assertEquals(AsrLanguage.SPANISH, AsrLanguage.fromCode("es_419"))
        assertEquals(AsrLanguage.JAPANESE, AsrLanguage.fromCode("ja-JP"))
        assertEquals(AsrLanguage.ENGLISH, AsrLanguage.fromCode("EN-us"))
        assertEquals(AsrLanguage.MANDARIN, AsrLanguage.fromCode("zh-CN"))
        assertEquals(AsrLanguage.MANDARIN, AsrLanguage.fromCode("cmn-Hans-CN"))
        assertEquals(AsrLanguage.ARABIC, AsrLanguage.fromCode("ar-EG"))
        assertEquals(AsrLanguage.TAGALOG, AsrLanguage.fromCode("fil-PH"))
        assertEquals(AsrLanguage.TAGALOG, AsrLanguage.fromCode("tl_PH"))
        assertEquals(AsrLanguage.VIETNAMESE, AsrLanguage.fromCode("vi-VN"))
        assertNull(AsrLanguage.fromCode("yue-HK"))
        assertNull(AsrLanguage.fromCode("zh-yue-HK"))
        assertNull(AsrLanguage.fromCode("ko-KR"))
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
        assertEquals(8, AsrLanguage.entries.map { it.directory }.toSet().size)
        assertEquals(8, AsrLanguage.entries.map { it.checkpointIdentity }.toSet().size)
        AsrLanguage.entries.forEach { language ->
            val files = MoonshineModelFiles.artifacts(language)
            assertEquals(8, files.size)
            assertTrue(files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.bytes > 0 &&
                it.urls.single().contains("${language.modelVariant.pathName}-${language.code}/quantized_${language.modelDate}/") })
        }
    }
    @Test fun `existing Small models keep their directory and checkpoint identities`() {
        listOf(AsrLanguage.ENGLISH, AsrLanguage.GERMAN, AsrLanguage.SPANISH, AsrLanguage.JAPANESE).forEach { language ->
            assertEquals(AsrModelVariant.SMALL_STREAMING, language.modelVariant)
            assertEquals(4, language.modelVariant.nativeArchitecture)
            assertEquals("moonshine-small-streaming-${language.code}-${language.modelDate.replace('_', '-')}", language.directory)
            assertEquals("moonshine-small-streaming-${language.code}-quantized_${language.modelDate}-v1", language.checkpointIdentity)
        }
    }
    @Test fun `new languages use the Tiny ABI and their own verified compact models`() {
        val totals = mapOf(
            AsrLanguage.MANDARIN to 32_290_152L, AsrLanguage.ARABIC to 32_349_411L,
            AsrLanguage.TAGALOG to 32_309_481L, AsrLanguage.VIETNAMESE to 32_309_008L,
        )
        totals.forEach { (language, bytes) ->
            assertEquals(AsrModelVariant.TINY_STREAMING, language.modelVariant)
            assertEquals(2, language.modelVariant.nativeArchitecture)
            assertEquals(bytes, MoonshineModelFiles.artifacts(language).sumOf { it.bytes })
            assertTrue(language.directory.startsWith("moonshine-tiny-streaming-"))
        }
    }
    @Test fun `language aliases remain canonical across manual channel and feed selection`() {
        assertEquals("tl", resolveAsrLanguage(null, null, "fil-PH"))
        assertEquals("zh", resolveAsrLanguage("cmn", "ar", "vi-VN"))
        assertEquals("ar", resolveAsrLanguage(null, "arb", "fil-PH"))
        assertEquals("vi", resolveAsrLanguage("vie", null, "zh-TW"))
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

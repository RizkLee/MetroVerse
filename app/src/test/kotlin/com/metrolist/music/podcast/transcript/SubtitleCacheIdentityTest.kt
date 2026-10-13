/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import com.metrolist.music.constants.DEFAULT_PODCAST_AI_SYSTEM_PROMPT
import org.junit.Assert.*
import org.junit.Test

class SubtitleCacheIdentityTest {
    private val config = SubtitleTranslationConfig(
        "Custom", "first-key", "", "", "https://example.org", "model",
        "Translated", "ja", "",
    )

    @Test fun `equivalent custom API roots and effective default prompts share paid translations`() {
        assertEquals(config.cacheFingerprint(), config.copy(baseUrl = "https://example.org/v1/chat/completions").cacheFingerprint())
        assertEquals(config.cacheFingerprint(), config.copy(systemPrompt = DEFAULT_PODCAST_AI_SYSTEM_PROMPT).cacheFingerprint())
    }

    @Test fun `podcast prompt changes invalidate results but changing credentials only restarts requests`() {
        assertNotEquals(config.cacheFingerprint(), config.copy(systemPrompt = "Use casual spoken language").cacheFingerprint())
        assertEquals(config.cacheFingerprint(), config.copy(apiKey = "replacement-key").cacheFingerprint())
        assertNotEquals(config.requestFingerprint(), config.copy(apiKey = "replacement-key").requestFingerprint())
    }

    @Test fun `invalid saved URLs produce a recoverable request error rather than crashing cache lookup`() {
        assertEquals(64, config.copy(baseUrl = "old malformed value").cacheFingerprint().length)
    }
}

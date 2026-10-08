/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationSchedulingTest {
    private val cues = (0 until 100).map { index ->
        val cue = SubtitleCue(index * 1_000L, index * 1_000L + 900, "cue-$index")
        TranslationCue(cue, subtitleCueFingerprint(cue))
    }

    private val config = SubtitleTranslationConfig("OpenRouter", "test-key", "", "default", "", "model", "standard", "de", "prompt")

    @Test
    fun `nearby missing cues take priority over a large future window`() {
        val plan = planSubtitleTranslation(cues, emptySet(), 60, 1_000, 0, null) as SubtitleTranslationPlan.Batch
        assertTrue(60 in plan.indices)
        assertTrue(plan.indices.all { it >= 59 })
        assertTrue(plan.indices.size <= 24)
    }

    @Test
    fun `a new missing cue at the distant moving edge waits for a useful batch`() {
        val translated = cues.take(49).mapTo(mutableSetOf()) { it.key }
        val plan = planSubtitleTranslation(cues, translated, 1, 1_000, 1_000, 0)
        assertEquals(SubtitleTranslationPlan.Wait(31_000), plan)
        val later = planSubtitleTranslation(cues, translated, 1, 31_000, 1_000, 0) as SubtitleTranslationPlan.Batch
        assertEquals(listOf(49), later.indices)
    }

    @Test
    fun `prefetch starts when enough distant missing lines accumulate`() {
        val translated = cues.take(5).mapTo(mutableSetOf()) { it.key }
        val plan = planSubtitleTranslation(cues, translated, 0, 1_000, 1_000, null)
        assertTrue(plan is SubtitleTranslationPlan.Batch)
    }

    @Test
    fun `new ASR lines can join an urgent request during its short coalescing window`() {
        assertEquals(SubtitleTranslationPlan.Wait(750), planSubtitleTranslation(cues.take(1), emptySet(), 0, 0, 0, null))
        val plan = planSubtitleTranslation(cues.take(4), emptySet(), 0, 750, 0, null) as SubtitleTranslationPlan.Batch
        assertEquals(listOf(0, 1, 2, 3), plan.indices)
    }

    @Test
    fun `large previous cue does not displace the currently playing missing cue`() {
        val huge = cues.toMutableList()
        val prior = huge[4].cue.copy(text = "x".repeat(7_000))
        huge[4] = TranslationCue(prior, subtitleCueFingerprint(prior))
        val plan = planSubtitleTranslation(huge, emptySet(), 5, 1_000, 0, null) as SubtitleTranslationPlan.Batch
        assertTrue(5 in plan.indices)
    }

    @Test
    fun `rate limits respect retry-after and ordinary errors back off`() {
        val cooldown = SubtitleTranslationCooldown()
        cooldown.failed(1_000, 429, 120_000)
        assertEquals(121_000L, cooldown.retryAtMs)
        cooldown.failed(121_000, 429)
        assertEquals(181_000L, cooldown.retryAtMs)
        cooldown.succeeded()
        cooldown.failed(0)
        assertEquals(10_000L, cooldown.retryAtMs)
        cooldown.failed(10_000)
        assertEquals(30_000L, cooldown.retryAtMs)
    }

    @Test
    fun `authentication errors wait for configuration changes`() {
        val cooldown = SubtitleTranslationCooldown()
        cooldown.failed(0, 401)
        assertTrue(cooldown.requiresExplicitRetry)
        cooldown.succeeded()
        assertFalse(cooldown.requiresExplicitRetry)
    }

    @Test
    fun `invalid endpoints and exhausted quota stop automatically while transient failures have a budget`() {
        listOf(400, 401, 402, 403, 404, 422).forEach { code ->
            val cooldown = SubtitleTranslationCooldown()
            cooldown.failed(0, code)
            assertTrue("HTTP $code", cooldown.requiresExplicitRetry)
        }
        val transient = SubtitleTranslationCooldown()
        transient.failed(0)
        transient.failed(10_000)
        assertFalse(transient.requiresExplicitRetry)
        transient.failed(30_000)
        assertTrue(transient.requiresExplicitRetry)
    }

    @Test
    fun `pause or EOF flushes a small pending streaming batch`() {
        val translated = setOf(cues[0].key)
        val live = planSubtitleTranslation(cues.take(2), translated, 1, 5_000, 4_000, 0, isStreaming = true)
        assertEquals(SubtitleTranslationPlan.Wait(16_000), live)
        val stopped = planSubtitleTranslation(cues.take(2), translated, 1, 5_000, 4_000, 0, isStreaming = false)
        assertEquals(SubtitleTranslationPlan.Batch(listOf(1)), stopped)
    }

    @Test
    fun `cache identity includes output settings while credential rotation only restarts requests`() {
        listOf(
            config.copy(model = "another model"), config.copy(mode = "another mode"),
            config.copy(systemPrompt = "another prompt"), config.copy(targetLanguage = "fr"),
            config.copy(baseUrl = "https://example.org/translate"),
        ).forEach { assertNotEquals(config.cacheFingerprint(), it.cacheFingerprint()) }
        val newKey = config.copy(apiKey = "replacement-test-key")
        assertEquals(config.cacheFingerprint(), newKey.cacheFingerprint())
        assertNotEquals(config.requestFingerprint(), newKey.requestFingerprint())
        val deepl = config.copy(provider = "DeepL", deeplApiKey = "test-key")
        assertNotEquals(deepl.cacheFingerprint(), deepl.copy(deeplFormality = "more").cacheFingerprint())
    }
}

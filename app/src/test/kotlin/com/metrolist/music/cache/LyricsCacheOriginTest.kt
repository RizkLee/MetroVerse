package com.metrolist.music.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCacheOriginTest {
    @Test
    fun `only an unchanged automatically fetched original is eligible`() {
        val original = "Fetched lyrics\n第二行"
        val proof = lyricsCacheFingerprint(original)
        assertTrue(hasVerifiedCacheOrigin(original, proof))
        assertFalse(hasVerifiedCacheOrigin("Edited lyrics\n第二行", proof))
        assertFalse(hasVerifiedCacheOrigin(original, null))
        assertFalse(hasVerifiedCacheOrigin(original, "damaged-index"))
    }

    @Test
    fun `unmarked legacy lyrics stay protected when quota is exceeded`() {
        val old = TextCacheEntry("legacy", TextCacheCategory.LYRICS, 100, 0, unverified = true)
        val cached = TextCacheEntry("cached", TextCacheCategory.LYRICS, 50, 1)
        val evictions = textCacheEvictions(listOf(old, cached), 25)
        assertTrue(cached in evictions)
        assertFalse(old in evictions)
    }
}

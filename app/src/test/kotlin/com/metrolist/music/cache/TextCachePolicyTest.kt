/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCachePolicyTest {
    @Test
    fun `podcast groups are removed before lyrics and oldest podcast goes first`() {
        val entries = listOf(
            TextCacheEntry("recent-podcast", TextCacheCategory.PODCAST, 20, 300),
            TextCacheEntry("old-lyrics", TextCacheCategory.LYRICS, 60, 1),
            TextCacheEntry("older-podcast", TextCacheCategory.PODCAST, 80, 200),
        )

        assertEquals(
            listOf("older-podcast", "recent-podcast", "old-lyrics"),
            textCacheRemovalOrder(entries).map { it.id },
        )
        // An episode's original and translated text form one indivisible 80-byte entry.
        assertEquals(listOf("older-podcast"), textCacheEvictions(entries, 100).map { it.id })
    }

    @Test
    fun `lyrics keep library and downloaded content ahead of older ordinary content`() {
        fun lyrics(id: String, lastAccess: Long, hasSong: Boolean, library: Boolean, liked: Boolean, downloaded: Boolean) =
            TextCacheEntry(
                id = id,
                category = TextCacheCategory.LYRICS,
                bytes = 10,
                lastAccessMs = lastAccess,
                priority = lyricCachePriority(hasSong, library, liked, downloaded),
            )

        val entries = listOf(
            lyrics("downloaded", 1, true, false, false, true),
            lyrics("library", 3, true, true, false, false),
            lyrics("ordinary-newer", 300, true, false, false, false),
            lyrics("liked", 2, true, false, true, false),
            lyrics("orphan", 400, false, false, false, false),
            lyrics("ordinary-older", 200, true, false, false, false),
        )

        assertEquals(
            listOf("orphan", "ordinary-older", "ordinary-newer", "liked", "library", "downloaded"),
            textCacheRemovalOrder(entries).map { it.id },
        )
        assertEquals(listOf("orphan", "ordinary-older", "ordinary-newer"), textCacheEvictions(entries, 30).map { it.id })
    }

    @Test
    fun `protected entries survive even when they alone exceed the limit`() {
        val entries = listOf(
            TextCacheEntry("active-podcast", TextCacheCategory.PODCAST, 170, 1, protected = true),
            TextCacheEntry("manual-lyrics", TextCacheCategory.LYRICS, 20, 1, protected = true),
            TextCacheEntry("ordinary", TextCacheCategory.LYRICS, 10, 2),
        )

        assertEquals(listOf("ordinary"), textCacheEvictions(entries, 100).map { it.id })
        assertTrue(textCacheEvictions(entries.filter { it.protected }, 0).isEmpty())
    }

    @Test
    fun `cache exactly at its limit keeps every entry`() {
        val entries = listOf(
            TextCacheEntry("podcast", TextCacheCategory.PODCAST, 40, 1),
            TextCacheEntry("lyrics", TextCacheCategory.LYRICS, 60, 2),
        )
        assertTrue(textCacheEvictions(entries, 100).isEmpty())
        assertTrue(textCacheEvictions(entries, 101).isEmpty())
        assertEquals(listOf("podcast"), textCacheEvictions(entries, 99).map { it.id })
    }
}

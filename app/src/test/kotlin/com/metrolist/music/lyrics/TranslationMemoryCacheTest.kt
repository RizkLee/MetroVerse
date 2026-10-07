package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationMemoryCacheTest {
    @Test
    fun `access renews LRU order`() {
        val cache = TranslationMemoryCache(maxEntries = 2)
        cache["first"] = listOf("one")
        cache["second"] = listOf("two")
        assertEquals(listOf("one"), cache["first"])
        cache["third"] = listOf("three")
        assertNull(cache["second"])
        assertEquals(listOf("one"), cache["first"])
    }

    @Test
    fun `oversized translations are not retained`() {
        val cache = TranslationMemoryCache(maxBytes = 20)
        cache["a"] = listOf("ok")
        cache["b"] = listOf("x".repeat(50))
        assertNull(cache["b"])
        assertEquals(listOf("ok"), cache["a"])
    }

    @Test
    fun `byte quota evicts enough entries and clear removes all results`() {
        val cache = TranslationMemoryCache(maxBytes = 24)
        cache["a"] = listOf("1234")
        cache["b"] = listOf("1234")
        cache["c"] = listOf("123456789")
        assertNull(cache["a"])
        assertNull(cache["b"])
        assertEquals(listOf("123456789"), cache["c"])
        cache.clear()
        assertNull(cache["c"])
    }
}

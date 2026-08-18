/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsProviderRegistryTest {
    @Test
    fun `KuGou is last in the default provider order`() {
        val order = LyricsProviderRegistry.getDefaultProviderOrder()

        assertEquals("KuGou", order.last())
        assertEquals(order.size, order.distinct().size)
        assertEquals(LyricsProviderRegistry.providerNames.toSet(), order.toSet())
    }
}

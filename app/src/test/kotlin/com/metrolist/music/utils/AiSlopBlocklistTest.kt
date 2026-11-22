/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class AiSlopBlocklistTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses valid handles and channel ids and rejects comments`() {
        val raw =
            """
            # Override92/AiSList
            @AiCreatorHandle
            UC1234567890123456789012
            not-a-handle
            @another_slop
            """.trimIndent()
        val parsed = AiSlopBlocklist.parse(raw)
        assertEquals(setOf("@aicreatorhandle", "uc1234567890123456789012", "@another_slop"), parsed)

        assertTrue(AiSlopBlocklist.matches("UC1234567890123456789012", "Other", parsed))
        assertTrue(AiSlopBlocklist.matches(null, "AiCreatorHandle", parsed))
        assertTrue(AiSlopBlocklist.matches(null, "@AiCreatorHandle", parsed))
        assertFalse(AiSlopBlocklist.matches("UC0000000000000000000000", "LegitArtist", parsed))
    }

    @Test
    fun `enforces size caps on declared header stream and empty payloads`() {
        assertThrows(IllegalStateException::class.java) {
            AiSlopBlocklist.checkDeclaredSize(AiSlopBlocklist.MAX_RESPONSE_BYTES.toLong() + 1L)
        }
        assertThrows(IllegalStateException::class.java) {
            val oversized = ByteArray(AiSlopBlocklist.MAX_RESPONSE_BYTES + 1) { 'a'.code.toByte() }
            AiSlopBlocklist.readBoundedText(ByteArrayInputStream(oversized))
        }
        assertThrows(IllegalStateException::class.java) {
            AiSlopBlocklist.parseDownloaded("# empty comment list")
        }
    }

    @Test
    fun `atomically writes and reloads cache file in cacheDir`() {
        val cacheDir = tempFolder.newFolder("aislist")
        AiSlopBlocklist.writeCacheFile(cacheDir, "@FirstAi\nUC1234567890123456789012\n")
        AiSlopBlocklist.writeCacheFile(cacheDir, "@UpdatedAi\n")
        val reloaded = AiSlopBlocklist.readCacheFile(cacheDir)
        assertEquals(setOf("@updatedai"), reloaded)
        assertFalse(cacheDir.resolve("aislist_blocklist.txt.tmp").exists())
    }
}

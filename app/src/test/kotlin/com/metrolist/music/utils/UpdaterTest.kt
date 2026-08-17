/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun `release asset parser accepts current foss and gms names`() {
        assertEquals(
            ParsedReleaseAssetName("v0.5.1", "foss"),
            parseReleaseAssetName("MetroVerse-v0.5.1-foss.apk"),
        )
        assertEquals(
            ParsedReleaseAssetName("v0.5.1", "gms"),
            parseReleaseAssetName("MetroVerse-v0.5.1-gms.apk"),
        )
    }

    @Test
    fun `release asset parser rejects removed or unrelated variants`() {
        assertNull(parseReleaseAssetName("MetroVerse-v0.5.1-izzy.apk"))
        assertNull(parseReleaseAssetName("another-app-v0.5.1-foss.apk"))
        assertNull(parseReleaseAssetName("MetroVerse-v0.5.1-SHA256SUMS.txt"))
    }

    @Test
    fun `checksum parser selects the exact apk`() {
        val expected = "a".repeat(64)
        val checksums =
            """
            ${"b".repeat(64)}  MetroVerse-v0.5.1-gms.apk
            $expected *MetroVerse-v0.5.1-foss.apk
            """.trimIndent()

        assertEquals(
            expected,
            parseSha256Checksum(checksums, "MetroVerse-v0.5.1-foss.apk"),
        )
        assertNull(parseSha256Checksum(checksums, "MetroVerse-v0.5.1-izzy.apk"))
    }

    @Test
    fun `semantic versions compare stable and prerelease tags`() {
        assertTrue(Updater.compareVersions("v0.5.1", "0.4.0") > 0)
        assertTrue(Updater.compareVersions("v1.0.0", "v0.99.99") > 0)
        assertTrue(Updater.compareVersions("v0.5.1", "v0.5.1-rc.1") > 0)
        assertTrue(Updater.compareVersions("v0.5.1-rc.2", "v0.5.1-rc.1") > 0)
        assertEquals(0, Updater.compareVersions("v0.5.1", "0.5.1"))
    }

    @Test
    fun `update availability only accepts newer versions`() {
        assertTrue(Updater.isUpdateAvailable("0.4.0", "v0.5.1"))
        assertFalse(Updater.isUpdateAvailable("0.5.1", "v0.5.1"))
        assertFalse(Updater.isUpdateAvailable("0.6.0", "v0.5.1"))
    }
}

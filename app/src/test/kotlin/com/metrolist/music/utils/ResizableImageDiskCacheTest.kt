/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResizableImageDiskCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `defers resize until active snapshot closes and clears when resized to zero`() {
        val cacheDir = tempFolder.newFolder("coil_cache").toOkioPath()
        val cache = ResizableImageDiskCache(cacheDir, maxSizeBytes = 2L * 1024 * 1024)
        try {
            val editor = cache.openEditor("cover-1")
            assertNotNull(editor)
            cache.fileSystem.write(editor!!.data) {
                writeUtf8("image-bytes")
            }
            val snapshot = editor.commitAndOpenSnapshot()
            assertNotNull(snapshot)

            // Resizing to zero while snapshot is open defers reopening and blocks new opens
            cache.resize(0L)
            assertEquals(0L, cache.maxSize)
            assertNull(cache.openEditor("cover-2"))

            snapshot!!.close()

            // After snapshot closes, clear/resize applies immediately
            assertEquals(0L, cache.size)
            assertNull(cache.openSnapshot("cover-1"))

            // Growing budget back re-enables caching cleanly
            cache.resize(1024L * 1024L)
            val editor2 = cache.openEditor("cover-3")
            assertNotNull(editor2)
            editor2!!.abort()
        } finally {
            cache.shutdown()
        }
    }
}

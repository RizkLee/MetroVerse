/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetiredAsrModelCleanupTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `cleanup removes only retired weights and keeps the active model`() {
        val root = temporaryFolder.newFolder()
        val retired = root.resolve("podcast/asr/zipformer-en").apply { mkdirs() }
        retired.resolve("encoder.int8.onnx").writeText("retired")
        val active = root.resolve("podcast/asr/current").apply { mkdirs() }
        val weights = active.resolve("model.ort").apply { writeText("current") }
        removeRetiredAsrModelFiles(root)
        assertFalse(retired.exists())
        assertTrue(weights.exists())
    }

    @Test
    fun `retired directory link does not delete the active model`() {
        val root = temporaryFolder.newFolder()
        val active = root.resolve("podcast/asr/current").apply { mkdirs() }
        val weights = active.resolve("model.ort").apply { writeText("current") }
        val link = root.resolve("podcast/asr/zipformer-en").toPath()
        try {
            Files.createSymbolicLink(link, active.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        removeRetiredAsrModelFiles(root)
        assertTrue(weights.exists())
        assertFalse(Files.isSymbolicLink(link))
    }
}

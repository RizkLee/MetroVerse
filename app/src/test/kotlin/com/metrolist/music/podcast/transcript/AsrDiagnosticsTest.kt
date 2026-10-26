/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AsrDiagnosticsTest {
    private val messages = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == "PodcastAsr") messages += message
        }
    }

    @Before
    fun setUp() { Timber.plant(tree) }

    @After
    fun tearDown() { Timber.uproot(tree) }

    @Test
    fun missingCueMeasurementsCannotBeMistakenForAnEmptyTranscript() {
        val diagnostics = AsrDiagnostics(AsrLanguage.ENGLISH.model(AsrModelVariant.TINY_STREAMING))
        diagnostics.record("model_loaded")
        diagnostics.record("progress", 2_000, 0)
        diagnostics.record("stream_rotated", 314_000, 44)
        diagnostics.record("model_released", 314_000)
        assertEquals(4, messages.size)
        assertFalse(messages[0].contains("cues="))
        assertTrue(messages[1].contains(" cues=0 "))
        assertTrue(messages[2].contains("stage=stream_rotated "))
        assertTrue(messages[2].contains(" cues=44 "))
        assertFalse(messages[3].contains("cues="))
    }

    @Test
    fun initialResumePositionIsDistinctFromCurrentMediaPositionAndAudioProcessed() {
        val diagnostics = AsrDiagnostics(AsrLanguage.ENGLISH.model(AsrModelVariant.TINY_STREAMING))
        diagnostics.resume(600_000, 600_000, 100, "saved_checkpoint")
        diagnostics.addInference(25, 32_000)
        diagnostics.record("progress", 650_000, 122)
        diagnostics.record("recognition_finished", 900_000, 180)
        assertTrue(messages[0].contains("initialResumeMs=600000 coveredMs=600000 retainedCues=100 reason=saved_checkpoint"))
        assertTrue(messages[1].contains("audioMs=650000 initialResumeMs=600000 inferredAudioMs=2000 inferenceMs=25"))
        assertTrue(messages[2].contains("audioMs=900000 initialResumeMs=600000"))
    }

    @Test
    fun memorySamplesIdentifyTheActualVariantAndLanguage() {
        AsrDiagnostics(AsrLanguage.ENGLISH.model(AsrModelVariant.TINY_STREAMING)).record("model_loaded")
        AsrDiagnostics(AsrLanguage.JAPANESE.model(AsrModelVariant.SMALL_STREAMING)).record("model_loaded")
        assertTrue(messages[0].contains("model=tiny-streaming language=en "))
        assertTrue(messages[1].contains("model=small-streaming language=ja "))
        messages.forEach {
            assertTrue(it.contains(" javaKiB="))
            assertTrue(it.contains(" nativeKiB="))
            assertTrue(it.contains(" pssKiB="))
        }
    }
}

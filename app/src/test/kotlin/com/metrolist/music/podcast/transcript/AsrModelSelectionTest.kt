/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrModelSelectionTest {
    private val tiny = AsrModelVariant.TINY_STREAMING
    private val small = AsrModelVariant.SMALL_STREAMING

    @Test
    fun allTwelveModelsHaveIndependentStorageAndKeepExistingModelIdentities() {
        assertEquals(12, AsrModel.all.size)
        assertEquals(12, AsrModel.all.map { it.directory }.distinct().size)
        assertEquals(12, AsrModel.all.map { it.checkpointIdentity }.distinct().size)
        AsrLanguage.entries.forEach { language ->
            assertEquals(language.directory, language.model().directory)
            assertEquals(language.checkpointIdentity, language.model().checkpointIdentity)
        }
        assertThrows(IllegalArgumentException::class.java) { AsrLanguage.MANDARIN.model(small) }
    }

    @Test
    fun newInstallsPreferTinyAndExistingSmallDownloadsKeepTheirSelection() {
        listOf(AsrLanguage.ENGLISH, AsrLanguage.GERMAN, AsrLanguage.SPANISH, AsrLanguage.JAPANESE).forEach { language ->
            assertEquals(tiny, preferredAsrVariant(language, null, emptySet()))
            val ready = setOf(language.model(small), language.model(tiny))
            assertEquals(small, preferredAsrVariant(language, null, ready))
            assertEquals(tiny, preferredAsrVariant(language, tiny.pathName, ready))
            assertEquals(small, preferredAsrVariant(language, small.pathName, emptySet()))
        }
    }

    @Test
    fun unsupportedOrUnknownStoredVariantsFallBackToASupportedModel() {
        assertEquals(tiny, preferredAsrVariant(AsrLanguage.MANDARIN, small.pathName, emptySet()))
        assertEquals(tiny, preferredAsrVariant(AsrLanguage.ENGLISH, "unknown", emptySet()))
    }

    @Test
    fun onlyFinalizedCheckpointsFromKnownModelsOfTheSameLanguageAreCompatible() {
        val englishSmall = AsrLanguage.ENGLISH.model(small).checkpointIdentity
        val englishTiny = AsrLanguage.ENGLISH.model(tiny).checkpointIdentity
        assertTrue(compatibleAsrCheckpoints(englishSmall, englishTiny))
        assertTrue(compatibleAsrCheckpoints(englishTiny, englishSmall))
        assertFalse(compatibleAsrCheckpoints(AsrLanguage.GERMAN.model(tiny).checkpointIdentity, englishTiny))
        assertFalse(compatibleAsrCheckpoints(englishSmall.replace("-v1", "-v0"), englishTiny))
        assertFalse(compatibleAsrCheckpoints(null, englishTiny))
    }

    @Test
    fun switchingToTinyRestoresFinalizedTextAndCoveredAudioWithoutStartingOver() {
        val cues = listOf(SubtitleCue(1_000L, 5_000L, "Completed sentence"))
        val ranges = listOf(AsrAudioRange(0L, 5_000L))
        val checkpoint = EpisodeTranscript(
            "episode", TranscriptSource.ON_DEVICE, cues, "en", isComplete = false,
            resumePositionMs = 5_000L, mediaSourceFingerprint = "audio",
            recognizerFingerprint = AsrLanguage.ENGLISH.model(small).checkpointIdentity,
            processedRanges = ranges,
        )

        val restored = recoverAsrProgress(checkpoint, "audio", AsrLanguage.ENGLISH.model(tiny).checkpointIdentity, 90_000L)

        assertEquals(5_000L, restored.positionMs)
        assertEquals(cues, restored.cues)
        assertEquals(ranges, restored.ranges)
        assertEquals("saved_checkpoint", restored.reason)
    }

    @Test
    fun tinyManifestsUseVerifiedDownloadsAndTheTinyNativeArchitecture() {
        val bytes = mapOf(AsrLanguage.ENGLISH to 45_233_659L, AsrLanguage.GERMAN to 32_317_004L,
            AsrLanguage.SPANISH to 32_316_573L, AsrLanguage.JAPANESE to 32_319_961L)
        bytes.forEach { (language, expected) ->
            val model = language.model(tiny)
            val files = MoonshineModelFiles.artifacts(model)
            assertEquals(8, files.size)
            assertEquals(expected, files.sumOf { it.bytes })
            assertEquals(2, model.variant.nativeArchitecture)
            assertTrue(files.all { it.urls.single().contains("/tiny-streaming-${language.code}/quantized_${language.modelDate}/") })
        }
    }
}

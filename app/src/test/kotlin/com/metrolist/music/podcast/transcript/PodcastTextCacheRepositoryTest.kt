/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.cache.TextCacheSignals
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PodcastTextCacheRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var repository: TranscriptRepository
    private val episodeId = "rss_episode_${"a".repeat(40)}"
    private val key = episodeId.takeLast(48)
    private val germanConfiguration = subtitleCacheFingerprint("Mistral", "de", "model")
    private val frenchConfiguration = subtitleCacheFingerprint("DeepL", "fr")

    private fun lineKey(index: Int) = subtitleCueFingerprint(SubtitleCue(index * 1_000L, index * 1_000L + 500, "line $index"))

    @Before
    fun setUp() {
        filesDir = temporaryFolder.newFolder("files")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getFilesDir(): File = this@PodcastTextCacheRepositoryTest.filesDir
        }
        repository = TranscriptRepository(context)
    }

    @After
    fun clearProtection() {
        TextCacheSignals.protect("podcast_cache_test", null)
    }

    @Test
    fun `entry groups transcript and translations without counting discovery or models`() = runBlocking {
        repository.storeOnDeviceTranscript(episodeId, listOf(SubtitleCue(0, 1_000, "hello")), "en")
        assertTrue(repository.storeTranslation(episodeId, germanConfiguration, mapOf(lineKey(0) to "hallo")))
        assertTrue(repository.storeTranslation(episodeId, frenchConfiguration, mapOf(lineKey(0) to "bonjour")))
        val index = filesDir.resolve("podcast/transcript_index.json").apply { writeText("discovery") }
        val model = filesDir.resolve("podcast/asr/model.onnx").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(128))
        }
        val textFiles = filesDir.resolve("podcast/transcripts").listFiles().orEmpty().toList() +
            filesDir.resolve("podcast/translations").listFiles().orEmpty().toList()
        textFiles.forEachIndexed { indexInGroup, file -> assertTrue(file.setLastModified(1_000L + indexInGroup)) }

        val entry = repository.cachedTextEntries().single()
        assertEquals(key, entry.key)
        assertEquals(textFiles.sumOf { it.length() }, entry.bytes)
        assertEquals(1_002L, entry.lastAccessMs)
        assertTrue(repository.removeCachedText(key))
        assertTrue(index.exists())
        assertTrue(model.exists())
        assertTrue(repository.cachedTextEntries().isEmpty())
    }

    @Test
    fun `protected episode survives removal while another episode can be removed`() = runBlocking {
        val otherEpisodeId = "rss_episode_${"b".repeat(40)}"
        listOf(episodeId, otherEpisodeId).forEach {
            repository.storeOnDeviceTranscript(it, listOf(SubtitleCue(0, 1_000, "hello")))
            repository.storeTranslation(it, germanConfiguration, mapOf(lineKey(0) to "hallo"))
        }
        TextCacheSignals.protect("podcast_cache_test", episodeId)
        assertFalse(repository.removeCachedText(key))
        assertTrue(repository.removeCachedText(otherEpisodeId.takeLast(48)))
        assertNotNull(repository.cachedTranscript(episodeId))
        assertEquals(listOf(key), repository.cachedTextEntries().map { it.key })
        assertFalse(repository.removeCachedText("../transcript_index"))
    }

    @Test
    fun `parallel translation batches retain every completed batch`() = runBlocking {
        coroutineScope {
            repeat(24) { index ->
                launch(Dispatchers.IO) {
                    assertTrue(repository.storeTranslation(episodeId, germanConfiguration, mapOf(lineKey(index) to "line $index")))
                }
            }
        }
        assertEquals((0 until 24).associate { lineKey(it) to "line $it" }, repository.loadTranslation(episodeId, germanConfiguration))
        assertEquals(key, repository.cachedTextEntries().single().key)
    }

    @Test
    fun `reading cached text renews its access time`() = runBlocking {
        repository.storeOnDeviceTranscript(episodeId, listOf(SubtitleCue(0, 1_000, "hello")))
        val file = filesDir.resolve("podcast/transcripts/$key.json")
        assertTrue(file.setLastModified(1_000))
        val beforeRead = System.currentTimeMillis()
        assertNotNull(repository.cachedTranscript(episodeId))
        assertTrue(repository.cachedTextEntries().single().lastAccessMs >= beforeRead)
    }

    @Test
    fun `checkpoint retains explicit completion progress and safe resume boundary`() = runBlocking {
        val checkpoint = EpisodeTranscript(
            episodeId, TranscriptSource.ON_DEVICE, listOf(SubtitleCue(0, 1_000, "hello")), "en",
            isComplete = false, progressPercent = 42, resumePositionMs = 300_000,
            mediaSourceFingerprint = subtitleCacheFingerprint("audio"), recognizerFingerprint = "recognizer-v1",
        )
        assertTrue(repository.storeOnDeviceTranscript(checkpoint).isSuccess)
        assertEquals(checkpoint, repository.cachedTranscript(episodeId))
        val complete = checkpoint.copy(isComplete = true, progressPercent = 100)
        assertTrue(repository.storeOnDeviceTranscript(complete).isSuccess)
        assertEquals(complete, repository.cachedTranscript(episodeId))
    }

    @Test
    fun `legacy transcripts remain complete without treating unbound translations as verified`() = runBlocking {
        val transcript = filesDir.resolve("podcast/transcripts/$key.json").apply { parentFile?.mkdirs() }
        transcript.writeText("""{"source":"ON_DEVICE","cues":[{"s":0,"e":1000,"t":"hello"}]}""")
        val oldTranslation = filesDir.resolve("podcast/translations/" + key + "_Mistral_de.json").apply { parentFile?.mkdirs() }
        oldTranslation.writeText("""{"0":"hallo"}""")
        assertTrue(repository.cachedTranscript(episodeId)!!.isComplete)
        assertEquals(100, repository.cachedTranscript(episodeId)!!.progressPercent)
        assertTrue(repository.loadTranslation(episodeId, germanConfiguration).isEmpty())
        assertTrue(oldTranslation.exists())
    }

    @Test
    fun `growing source checkpoints retain translations outside the currently visible prefix`() = runBlocking {
        val first = SubtitleCue(0, 1_000, "hello")
        val later = SubtitleCue(1_000, 2_000, "world")
        val translations = mapOf(subtitleCueFingerprint(first) to "hallo", subtitleCueFingerprint(later) to "Welt")
        repository.storeTranslation(episodeId, germanConfiguration, translations)
        repository.storeOnDeviceTranscript(EpisodeTranscript(episodeId, TranscriptSource.ON_DEVICE, listOf(first), isComplete = false))
        assertEquals(translations, repository.loadTranslation(episodeId, germanConfiguration))
        repository.storeOnDeviceTranscript(episodeId, listOf(first, later))
        assertEquals(translations, repository.loadTranslation(episodeId, germanConfiguration))
        assertTrue(repository.loadTranslation(episodeId, frenchConfiguration).isEmpty())
    }

    @Test
    fun `failed atomic replacement reports failure and preserves the previous checkpoint`() = runBlocking {
        val saved = EpisodeTranscript(episodeId, TranscriptSource.ON_DEVICE, listOf(SubtitleCue(0, 1_000, "saved")), isComplete = false)
        assertTrue(repository.storeOnDeviceTranscript(saved).isSuccess)
        val pending = filesDir.resolve("podcast/transcripts/.$key.json.pending")
        assertTrue(pending.mkdir())
        pending.resolve("occupied").writeText("test")
        val next = saved.copy(cues = listOf(SubtitleCue(0, 2_000, "new")), isComplete = true, progressPercent = 100)
        assertTrue(repository.storeOnDeviceTranscript(next).isFailure)
        assertEquals(saved, repository.cachedTranscript(episodeId))
        assertEquals(next, repository.pendingTranscript(episodeId))
        assertTrue(pending.resolve("occupied").delete())
        assertTrue(pending.delete())
        assertTrue(repository.storeOnDeviceTranscript(next).isSuccess)
        assertNull(repository.pendingTranscript(episodeId))
        assertEquals(next, repository.cachedTranscript(episodeId))
    }

    @Test
    fun `a failed partial checkpoint survives another episode and explicit removal clears only its backup`() = runBlocking {
        val pending = filesDir.resolve("podcast/transcripts/.$key.json.pending")
        assertTrue(pending.mkdirs())
        pending.resolve("occupied").writeText("test")
        val partial = EpisodeTranscript(
            episodeId, TranscriptSource.ON_DEVICE, listOf(SubtitleCue(0, 1_000, "recognized")),
            isComplete = false, progressPercent = 25, resumePositionMs = 1_000,
            mediaSourceFingerprint = subtitleCacheFingerprint("audio"), recognizerFingerprint = "recognizer-v1",
        )
        assertTrue(repository.storeOnDeviceTranscript(partial).isFailure)
        val otherEpisodeId = "rss_episode_${"b".repeat(40)}"
        assertTrue(repository.storeOnDeviceTranscript(otherEpisodeId, listOf(SubtitleCue(0, 1_000, "other"))).isSuccess)
        assertEquals(partial, repository.pendingTranscript(episodeId))
        assertNull(repository.cachedTranscript(episodeId))
        assertEquals(0L, repository.cachedTextEntries().single { it.key == key }.bytes)

        TextCacheSignals.protect("podcast_cache_test", episodeId)
        assertFalse(repository.removeCachedText(key))
        assertEquals(partial, repository.pendingTranscript(episodeId))
        TextCacheSignals.protect("podcast_cache_test", null)
        assertTrue(repository.removeCachedText(key))
        assertNull(repository.pendingTranscript(episodeId))
        assertNotNull(repository.cachedTranscript(otherEpisodeId))
        assertEquals(listOf(otherEpisodeId.takeLast(48)), repository.cachedTextEntries().map { it.key })
    }

    @Test
    fun `failed translation save remains discoverable and removable without a disk transcript`() = runBlocking {
        val pending = filesDir.resolve("podcast/translations/.${key}_v2_$germanConfiguration.json.pending")
        assertTrue(pending.mkdirs())
        pending.resolve("occupied").writeText("test")
        val paid = mapOf(lineKey(0) to "hallo")
        assertFalse(repository.storeTranslation(episodeId, germanConfiguration, paid))
        assertTrue(repository.hasPendingTranslationSave(episodeId, germanConfiguration))
        assertEquals(paid, repository.loadTranslation(episodeId, germanConfiguration))
        assertEquals(0L, repository.cachedTextEntries().single().bytes)
        assertTrue(repository.removeCachedText(key))
        assertFalse(repository.hasPendingTranslationSave(episodeId, germanConfiguration))
        assertTrue(repository.loadTranslation(episodeId, germanConfiguration).isEmpty())
        assertTrue(repository.cachedTextEntries().isEmpty())
    }

    @Test
    fun `a later successful translation save retains the earlier failed paid batch`() = runBlocking {
        val pending = filesDir.resolve("podcast/translations/.${key}_v2_$germanConfiguration.json.pending")
        assertTrue(pending.mkdirs())
        pending.resolve("occupied").writeText("test")
        assertFalse(repository.storeTranslation(episodeId, germanConfiguration, mapOf(lineKey(0) to "hallo")))
        assertTrue(pending.resolve("occupied").delete())
        assertTrue(pending.delete())
        assertTrue(repository.storeTranslation(episodeId, germanConfiguration, mapOf(lineKey(1) to "Welt")))
        assertFalse(repository.hasPendingTranslationSave(episodeId, germanConfiguration))
        assertEquals(mapOf(lineKey(0) to "hallo", lineKey(1) to "Welt"), repository.loadTranslation(episodeId, germanConfiguration))
    }
}

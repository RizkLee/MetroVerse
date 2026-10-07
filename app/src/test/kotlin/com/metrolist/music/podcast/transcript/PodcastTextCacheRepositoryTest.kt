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
        assertTrue(repository.storeTranslation(episodeId, "de", "Mistral", mapOf(0 to "hallo")))
        assertTrue(repository.storeTranslation(episodeId, "fr", "DeepL", mapOf(0 to "bonjour")))
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
            repository.storeTranslation(it, "de", "Mistral", mapOf(0 to "hallo"))
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
                    assertTrue(repository.storeTranslation(episodeId, "de", "Mistral", mapOf(index to "line $index")))
                }
            }
        }
        assertEquals((0 until 24).associateWith { "line $it" }, repository.loadTranslation(episodeId, "de", "Mistral"))
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
}

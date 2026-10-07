package com.metrolist.music.cache

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.podcast.transcript.TranscriptRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
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
class TextCacheManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var room: InternalDatabase
    private lateinit var context: ContextWrapper
    private lateinit var files: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        files = temporaryFolder.newFolder("files")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getFilesDir(): File = files
        }
        room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        TextCacheSignals.protect("cache_manager_test", null)
        room.close()
    }

    private fun manager(): TextCacheManager = TextCacheManager(context, MusicDatabase(room), TranscriptRepository(context), scope)

    private fun origins(values: Map<String, String>) {
        files.resolve("text-cache/automatic.json").apply {
            parentFile?.mkdirs()
            writeText(Json.encodeToString(values))
        }
    }

    @Test
    fun `legacy lyrics require explicit inclusion and marked manual lyrics are always preserved`() = runBlocking {
        room.dao.upsert(LyricsEntity("legacy", "Older manual edits", provider = "LRCLIB"))
        room.dao.upsert(LyricsEntity("manual", "Marked manual edits", provider = "Manual"))
        val cache = manager()
        assertTrue(cache.clear(TextCacheClearTarget.LYRICS))
        assertNotNull(room.dao.lyrics("legacy").first())
        assertTrue(cache.clear(TextCacheClearTarget.LYRICS, includeUnverified = true))
        assertNull(room.dao.lyrics("legacy").first())
        assertNotNull(room.dao.lyrics("manual").first())
    }

    @Test
    fun `restored or edited text invalidates old cache proof`() = runBlocking {
        origins(mapOf("changed" to lyricsCacheFingerprint("Original downloaded lyrics")))
        room.dao.upsert(LyricsEntity("changed", "User text restored from an older backup", provider = "LRCLIB"))
        val cache = manager()
        assertTrue(cache.clear(TextCacheClearTarget.LYRICS))
        assertNotNull(room.dao.lyrics("changed").first())
        assertTrue(cache.state.value.protectedBytes > 0)
    }

    @Test
    fun `verified cache can be removed but active text is retained`() = runBlocking {
        origins(mapOf("cached" to lyricsCacheFingerprint("Fetched"), "active" to lyricsCacheFingerprint("Playing")))
        room.dao.upsert(LyricsEntity("cached", "Fetched", provider = "LRCLIB"))
        room.dao.upsert(LyricsEntity("active", "Playing", provider = "LRCLIB"))
        TextCacheSignals.protect("cache_manager_test", "active")
        assertTrue(manager().clear(TextCacheClearTarget.ALL, includeUnverified = true))
        assertNull(room.dao.lyrics("cached").first())
        assertNotNull(room.dao.lyrics("active").first())
    }
}

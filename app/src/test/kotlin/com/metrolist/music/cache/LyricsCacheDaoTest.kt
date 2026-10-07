/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.cache

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class LyricsCacheDaoTest {
    private lateinit var database: InternalDatabase
    private lateinit var dao: DatabaseDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dao
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `projection counts utf8 bytes for original and translated lyrics`() = runBlocking {
        val lyrics = LyricsEntity(
            id = "unicode",
            lyrics = "原词 🎵\nCafé",
            provider = "LRCLIB",
            translatedLyrics = "Übersetzung 🙂",
        )
        dao.upsert(lyrics)

        val entry = dao.lyricsCacheEntries().single()
        val expectedBytes = lyrics.lyrics.toByteArray(Charsets.UTF_8).size.toLong() +
            lyrics.translatedLyrics.toByteArray(Charsets.UTF_8).size
        assertEquals(expectedBytes, entry.bytes)
        assertEquals(lyrics.id, entry.id)
        assertFalse(entry.hasSong)
        assertFalse(entry.inLibrary)
        assertFalse(entry.liked)
        assertFalse(entry.downloaded)
        assertEquals(0L, entry.lastPlayedMs)
        assertEquals(listOf(entry), dao.observeLyricsCacheEntries().first())
    }

    @Test
    fun `manual lyrics cannot be evicted through the cache delete query`() = runBlocking {
        val manual = LyricsEntity("manual", "My own text", provider = "Manual", translatedLyrics = "My translation")
        dao.upsert(manual)
        dao.upsert(LyricsEntity("automatic", "Fetched text", provider = "LRCLIB"))

        assertEquals(0, dao.deleteCachedLyrics(manual.id))
        assertEquals(manual, dao.lyrics(manual.id).first())
        assertEquals(1, dao.deleteCachedLyrics("automatic"))
        assertNull(dao.lyrics("automatic").first())
        assertEquals(listOf(manual.id), dao.lyricsCacheEntries().map { it.id })
    }

    @Test
    fun `deleting lyrics preserves song download progress library and playback history`() = runBlocking {
        val savedAt = LocalDateTime.of(2026, 9, 1, 12, 0)
        val song = SongEntity(
            id = "saved-song",
            title = "Saved song",
            duration = 300,
            liked = true,
            likedDate = savedAt,
            inLibrary = savedAt,
            dateDownload = savedAt,
            isDownloaded = true,
            isCached = true,
            playbackPosition = 123_456L,
            totalPlayTime = 234_567L,
        )
        dao.insert(song)
        dao.insert(Event(songId = song.id, timestamp = savedAt, playTime = 30_000))
        dao.upsert(LyricsEntity(song.id, "Original", provider = "LRCLIB", translatedLyrics = "Translation"))

        val entry = dao.lyricsCacheEntries().single()
        assertTrue(entry.hasSong)
        assertTrue(entry.inLibrary)
        assertTrue(entry.liked)
        assertTrue(entry.downloaded)
        assertTrue(entry.lastPlayedMs > 0)
        assertEquals(1, dao.deleteCachedLyrics(song.id))
        assertNull(dao.lyrics(song.id).first())
        assertEquals(song, dao.songEntity(song.id))
        database.openHelper.readableDatabase.query("SELECT count(*) FROM event WHERE songId = ?", arrayOf(song.id)).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }
}

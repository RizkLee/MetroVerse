/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class YouTubeMusicSyncTransactionTest {
    private lateinit var database: MusicDatabase
    private val savedAt = LocalDateTime.of(2026, 10, 1, 12, 0)
    private val syncedAt = savedAt.plusDays(1)

    @Before
    fun setUp() {
        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
        database = MusicDatabase(room)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test(timeout = 15_000)
    fun `library removal preserves likes and changes made after the request snapshot`() = runBlocking {
        val before = song("saved")
        database.insert(before)
        val current = withRecentPlayback(before)
        database.update(current)

        database.removeYouTubeLibraryMembership(before)

        assertEquals(current.copy(inLibrary = null), database.songEntity(before.id))
    }

    @Test(timeout = 15_000)
    fun `unlike preserves library download playback and lyric settings`() = runBlocking {
        val before = song("liked")
        database.insert(before)
        val current = withRecentPlayback(before)
        database.update(current)

        database.removeYouTubeLike(before)

        assertEquals(current.copy(liked = false, likedDate = null), database.songEntity(before.id))
    }

    @Test(timeout = 15_000)
    fun `remote additions read the current row inside a completing Room transaction`() = runBlocking {
        val before = song("existing").copy(liked = false, likedDate = null, inLibrary = null)
        database.insert(before)
        val current = withRecentPlayback(before)
        database.update(current)

        database.mergeYouTubeLikedSong(item(before.id), beforeRequest = null, likedAt = syncedAt)
        database.mergeYouTubeLibrarySong(item(before.id), beforeRequest = null, addedAt = syncedAt)

        assertEquals(
            current.copy(liked = true, likedDate = syncedAt, inLibrary = syncedAt),
            database.songEntity(before.id),
        )
        database.mergeYouTubeLikedSong(item("new"), beforeRequest = null, likedAt = syncedAt)
        database.mergeYouTubeLibrarySong(item("new"), beforeRequest = null, addedAt = syncedAt)
        val added = database.songEntity("new")!!
        assertTrue(added.liked)
        assertEquals(syncedAt, added.likedDate)
        assertEquals(syncedAt, added.inLibrary)
    }

    @Test(timeout = 15_000)
    fun `stable liked snapshots retain remote order while existing library dates are preserved`() = runBlocking {
        val first = song("first")
        val second = song("second")
        database.insert(first)
        database.insert(second)

        database.mergeYouTubeLikedSong(item(first.id), first, syncedAt)
        database.mergeYouTubeLikedSong(item(second.id), second, syncedAt.minusSeconds(1))
        database.mergeYouTubeLibrarySong(item(first.id), first, syncedAt)

        assertEquals(first.copy(likedDate = syncedAt), database.songEntity(first.id))
        assertEquals(second.copy(likedDate = syncedAt.minusSeconds(1)), database.songEntity(second.id))
    }

    @Test(timeout = 15_000)
    fun `new library members retain their insertion order`() = runBlocking {
        database.mergeYouTubeLibrarySong(item("older"), beforeRequest = null, addedAt = syncedAt)
        val later = syncedAt.plusNanos(50_000_000)
        database.mergeYouTubeLibrarySong(item("newer"), beforeRequest = null, addedAt = later)

        assertEquals(syncedAt, database.songEntity("older")!!.inLibrary)
        assertEquals(later, database.songEntity("newer")!!.inLibrary)
        assertEquals(listOf("older", "newer"), database.songsByCreateDateAsc().first().map { it.id })
    }

    @Test(timeout = 15_000)
    fun `likes and library entries changed while fetching are not removed or reordered`() = runBlocking {
        val before = song("changed")
        database.insert(before)
        val current = withRecentPlayback(before).copy(likedDate = syncedAt, inLibrary = syncedAt)
        database.update(current)

        database.removeYouTubeLike(before)
        database.removeYouTubeLibraryMembership(before)
        database.mergeYouTubeLikedSong(item(before.id), before, syncedAt.plusDays(1))
        database.mergeYouTubeLibrarySong(item(before.id), before, syncedAt.plusDays(1))

        assertEquals(current, database.songEntity(before.id))
        database.mergeYouTubeLikedSong(item(before.id), beforeRequest = null, likedAt = syncedAt.plusDays(2))
        assertEquals(current, database.songEntity(before.id))
    }

    @Test(timeout = 15_000)
    fun `remote responses do not undo local removals or recreate rows deleted while fetching`() = runBlocking {
        val before = song("removed")
        database.insert(before)
        val current = before.copy(liked = false, likedDate = null, inLibrary = null)
        database.update(current)

        database.mergeYouTubeLikedSong(item(before.id), before, syncedAt)
        database.mergeYouTubeLibrarySong(item(before.id), before, syncedAt)
        assertEquals(current, database.songEntity(before.id))

        database.delete(current)
        database.mergeYouTubeLikedSong(item(before.id), before, syncedAt)
        database.mergeYouTubeLibrarySong(item(before.id), before, syncedAt)
        assertNull(database.songEntity(before.id))
    }

    @Test(timeout = 15_000)
    fun `transactional rereads protect songs whose ownership changed after the snapshot`() = runBlocking {
        val protected = listOf(
            song("local").copy(isLocal = true),
            song("episode").copy(isEpisode = true),
            song("direct").copy(mediaUrl = "https://example.invalid/audio.mp3"),
            song("upload").copy(isUploaded = true),
            song("upload-id").copy(uploadEntityId = "upload"),
            song("empty-upload-id").copy(uploadEntityId = ""),
            song("rss_episode_legacy"),
        )
        protected.forEach { current ->
            val before = song(current.id)
            database.insert(before)
            database.update(current)
            database.removeYouTubeLike(before)
            database.removeYouTubeLibraryMembership(before)
            database.mergeYouTubeLikedSong(item(current.id), before, syncedAt)
            database.mergeYouTubeLibrarySong(item(current.id), before, syncedAt)
            assertEquals(current, database.songEntity(current.id))
        }
    }

    @Test(timeout = 15_000)
    fun `remote episodes and uploads cannot enter the music sync path`() = runBlocking {
        val remote = listOf(
            item("episode").copy(isEpisode = true),
            item("upload").copy(uploadEntityId = "upload"),
            item("empty-upload-id").copy(uploadEntityId = ""),
            item("rss_episode_legacy"),
        )
        remote.forEach {
            database.mergeYouTubeLikedSong(it, beforeRequest = null, likedAt = syncedAt)
            database.mergeYouTubeLibrarySong(it, beforeRequest = null, addedAt = syncedAt)
            assertNull(database.songEntity(it.id))
        }
    }

    @Test(timeout = 15_000)
    fun `unknown empty library response leaves the saved library intact`() = runBlocking {
        val saved = song("saved")
        database.insert(saved)

        youtubeMusicRemovalCandidates(database.librarySongEntitiesByNameAsc(), emptySet()).forEach {
            database.removeYouTubeLibraryMembership(it)
        }

        assertEquals(saved, database.songEntity(saved.id))
    }

    private fun withRecentPlayback(song: SongEntity) = song.copy(
        title = "Updated metadata",
        dateDownload = syncedAt,
        isDownloaded = true,
        isCached = true,
        playbackPosition = 45_000L,
        totalPlayTime = 100_000L,
        lyricsOffset = 250,
        romanizeLyrics = false,
    )

    private fun song(id: String) = SongEntity(
        id = id,
        title = id,
        liked = true,
        likedDate = savedAt,
        inLibrary = savedAt,
    )

    private fun item(id: String) = SongItem(
        id = id,
        title = id,
        artists = emptyList(),
        thumbnail = "https://example.invalid/art.jpg",
    )
}

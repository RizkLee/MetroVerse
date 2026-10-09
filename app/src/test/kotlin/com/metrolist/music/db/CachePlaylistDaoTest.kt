/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * MetroVerse modifications (C) 2026 Rizklee
 */

package com.metrolist.music.db

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.viewmodels.CachePlaylistTestCache
import com.metrolist.music.viewmodels.cachePlaylistTestFormat
import com.metrolist.music.viewmodels.observeCachePlaylistCandidates
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class CachePlaylistDaoTest {
    private lateinit var database: InternalDatabase
    private lateinit var dao: DatabaseDao
    private val savedAt = LocalDateTime.of(2026, 10, 1, 12, 0)

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
    fun `candidate query includes downloaded and orphaned marks but never unmarked prefetch`() = runBlocking {
        dao.insert(SongEntity(id = "marked", title = "Marked", dateDownload = savedAt))
        dao.insert(SongEntity(
            id = "downloaded",
            title = "Downloaded episode",
            dateDownload = savedAt,
            isDownloaded = true,
            isEpisode = true,
        ))
        dao.insert(SongEntity(id = "prefetched", title = "Prefetched", isCached = true))
        dao.insert(SongEntity(id = "unmarked", title = "Unmarked"))

        assertEquals(setOf("marked", "downloaded"), dao.cachePlaylistSongs().first().map { it.id }.toSet())
        assertEquals(
            setOf("prefetched", "unmarked"),
            dao.getSongsByIds(listOf("prefetched", "unmarked")).map { it.id }.toSet(),
        )
    }

    @Test
    fun `clearing an unchanged date preserves concurrently edited song fields`() = runBlocking {
        val original = SongEntity(
            id = "edited",
            title = "Before edit",
            dateDownload = savedAt,
            playbackPosition = 10_000,
        )
        dao.insert(original)
        val latest = original.copy(
            title = "After edit",
            liked = true,
            likedDate = savedAt.plusMinutes(1),
            inLibrary = savedAt.plusMinutes(2),
            playbackPosition = 45_000,
            totalPlayTime = 60_000,
            lyricsOffset = 250,
            isCached = true,
        )
        dao.update(latest)

        assertEquals(1, dao.clearCachePlaylistDateIfUnchanged(original.id, savedAt))
        assertEquals(latest.copy(dateDownload = null), dao.songEntity(original.id))
    }

    @Test
    fun `newer dates and completed downloads reject an older cleanup snapshot`() = runBlocking {
        val renewed = SongEntity(id = "renewed", title = "Renewed", dateDownload = savedAt)
        val downloaded = SongEntity(id = "downloaded", title = "Downloaded", dateDownload = savedAt)
        dao.insert(renewed)
        dao.insert(downloaded)
        val latest = renewed.copy(dateDownload = savedAt.plusSeconds(1))
        dao.update(latest)
        dao.updateDownloadedInfo(downloaded.id, true, savedAt)

        assertEquals(0, dao.clearCachePlaylistDateIfUnchanged(renewed.id, savedAt))
        assertEquals(0, dao.clearCachePlaylistDateIfUnchanged(downloaded.id, savedAt))
        assertEquals(latest, dao.songEntity(renewed.id))
        assertEquals(downloaded.copy(isDownloaded = true), dao.songEntity(downloaded.id))
    }

    @Test
    fun `batch cleanup uses the existing transaction and does not create missing dates`() = runBlocking {
        val songs = listOf(
            SongEntity(id = "first", title = "First", dateDownload = savedAt),
            SongEntity(id = "second", title = "Second", dateDownload = savedAt),
        )
        songs.forEach { dao.insert(it) }
        dao.insert(SongEntity(id = "unmarked", title = "Unmarked"))
        val updated = mutableListOf<Int>()

        MusicDatabase(database).withTransaction {
            songs.forEach { updated += clearCachePlaylistDateIfUnchanged(it.id, savedAt) }
        }

        assertEquals(listOf(1, 1), updated)
        songs.forEach { assertEquals(it.copy(dateDownload = null), dao.songEntity(it.id)) }
        assertEquals(0, dao.clearCachePlaylistDateIfUnchanged("unmarked", savedAt))
        assertEquals(0, dao.clearCachePlaylistDateIfUnchanged("missing", savedAt))
        assertNull(dao.songEntity("unmarked")?.dateDownload)
        assertTrue(dao.cachePlaylistSongs().first().isEmpty())
    }

    @Test
    fun `room snapshots match initial and changed listeners while metadata leaves registrations intact`() = runBlocking {
        val player = CachePlaylistTestCache()
        val download = CachePlaylistTestCache()
        val sharingJob = SupervisorJob()
        val sharingScope = CoroutineScope(sharingJob + Dispatchers.Unconfined)
        val candidates = dao.cachePlaylistSongs()
            .distinctUntilChanged()
            .shareIn(sharingScope, SharingStarted.WhileSubscribed(), replay = 1)
        val snapshots = Channel<List<Song>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Unconfined) {
            observeCachePlaylistCandidates(candidates, player.cache, download.cache).collect { songs ->
                val ids = songs.map { it.id }.toSet()
                assertEquals(ids, player.listenerKeys)
                assertEquals(ids, download.listenerKeys)
                snapshots.send(songs)
            }
        }

        try {
            snapshots.nextMatching { it.isEmpty() }
            val first = SongEntity(id = "first", title = "First", dateDownload = savedAt)
            player.setCachedLength(first.id, 128)
            dao.insert(first)
            snapshots.nextMatching { songs -> songs.map { it.id } == listOf(first.id) }
            val playerRegistrations = player.registrationCalls.toList()
            val downloadRegistrations = download.registrationCalls.toList()

            val edited = first.copy(title = "Edited", playbackPosition = 30_000)
            dao.update(edited)
            snapshots.nextMatching { songs -> songs.singleOrNull()?.song == edited }
            dao.upsert(cachePlaylistTestFormat(first.id, 128))
            snapshots.nextMatching { songs -> songs.singleOrNull()?.format?.contentLength == 128L }
            assertEquals(playerRegistrations, player.registrationCalls)
            assertEquals(downloadRegistrations, download.registrationCalls)

            val second = SongEntity(id = "second", title = "Second", dateDownload = savedAt.plusMinutes(1))
            download.setCachedLength(second.id, 128)
            dao.insert(second)
            snapshots.nextMatching { songs -> songs.map { it.id }.toSet() == setOf(first.id, second.id) }

            assertEquals(1, dao.clearCachePlaylistDateIfUnchanged(first.id, savedAt))
            snapshots.nextMatching { songs -> songs.map { it.id } == listOf(second.id) }
            assertEquals(setOf(second.id), player.listenerKeys)
            assertEquals(setOf(second.id), download.listenerKeys)
        } finally {
            observer.cancelAndJoin()
            sharingJob.cancelAndJoin()
        }
        assertTrue(player.listenerKeys.isEmpty())
        assertTrue(download.listenerKeys.isEmpty())
    }

    private suspend fun Channel<List<Song>>.nextMatching(predicate: (List<Song>) -> Boolean): List<Song> =
        withTimeout(5_000) {
            var snapshot = receive()
            while (!predicate(snapshot)) snapshot = receive()
            snapshot
        }
}

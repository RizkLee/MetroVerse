/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicSyncPolicyTest {
    private val savedAt = LocalDateTime.of(2026, 10, 1, 12, 0)

    @Test
    fun `exact counts accept whole numbers and known song labels`() {
        val cases = mapOf(
            "321" to 321,
            "321 songs" to 321,
            "1 song" to 1,
            "0 songs" to 0,
            "1,234 songs" to 1234,
            "1 234 titres" to 1234,
            "1\u202f234 titres" to 1234,
            "321 首歌曲" to 321,
            "321曲" to 321,
            "321곡" to 321,
            "321 Titel" to 321,
            "321 canciones" to 321,
            "321 brani" to 321,
            "321 músicas" to 321,
            "321 треков" to 321,
        )
        cases.forEach { (text, count) -> assertEquals(text, count, parseExactSongCount(text)) }
    }

    @Test
    fun `unknown approximate malformed and duration counts cannot authorize unlike`() {
        val cases = listOf(
            null, "", " ", "1.2K songs", "1 K songs", "1,2 mil", "about 321 songs", "321+ songs",
            "-321 songs", "1,23 songs", "12,34,567 songs", "1,234 567 songs", "1.234 songs",
            "99999999999999999999 songs", "321 views", "2小时", "2 hours", "321 unknown", "一百首歌曲",
        )
        cases.forEach { text -> assertNull(text, parseExactSongCount(text)) }
    }

    @Test
    fun `unlikes require an exact total matching unique remote song ids`() {
        val remoteIds = listOf("song", "song").toSet()
        assertTrue(canRemoveMissingYouTubeLikes("1 song", remoteIds))
        assertFalse(canRemoveMissingYouTubeLikes("2 songs", remoteIds))
        assertFalse(canRemoveMissingYouTubeLikes("0 songs", remoteIds))
        assertFalse(canRemoveMissingYouTubeLikes("1.2K songs", remoteIds))
        assertFalse(canRemoveMissingYouTubeLikes(null, remoteIds))
        assertTrue(canRemoveMissingYouTubeLikes("0 songs", emptySet()))
    }

    @Test
    fun `music removals protect every other source and legacy RSS ids`() {
        val song = song("youtube-music")
        val protected = listOf(
            song("local").copy(isLocal = true),
            song("episode").copy(isEpisode = true),
            song("direct").copy(mediaUrl = "https://example.invalid/audio.mp3"),
            song("empty-direct").copy(mediaUrl = ""),
            song("uploaded").copy(isUploaded = true),
            song("upload-id").copy(uploadEntityId = "upload"),
            song("empty-upload-id").copy(uploadEntityId = ""),
            song("rss_episode_legacy"),
        )
        assertEquals(
            listOf(song),
            youtubeMusicRemovalCandidates(protected + song, setOf("another-remote-song")),
        )
        assertTrue(youtubeMusicRemovalCandidates(listOf(song), setOf(song.id)).isEmpty())
    }

    @Test
    fun `empty library responses never authorize removals without a verified empty count`() {
        val local = listOf(song("song"))
        assertTrue(youtubeMusicRemovalCandidates(local, emptySet()).isEmpty())
        assertEquals(local, youtubeMusicRemovalCandidates(local, emptySet(), allowEmptyRemote = true))
    }

    @Test
    fun `sync membership updates preserve likes and every unrelated field`() {
        val original = song("song").copy(
            dateDownload = savedAt,
            isDownloaded = true,
            isCached = true,
            lyricsOffset = 123,
            playbackPosition = 45_000,
        )
        val removed = original.withLibraryMembership(isInLibrary = false)
        assertEquals(original.copy(inLibrary = null), removed)
        assertEquals(original, original.withLibraryMembership(isInLibrary = true, addedAt = savedAt.plusDays(1)))
        assertEquals(original, removed.withLibraryMembership(isInLibrary = true, addedAt = savedAt))

        val manualRemoval = original.toggleLibrary(syncToYouTube = false)
        assertFalse(manualRemoval.liked)
        assertNull(manualRemoval.likedDate)
        assertNull(manualRemoval.inLibrary)
    }

    private fun song(id: String) = SongEntity(
        id = id,
        title = id,
        liked = true,
        likedDate = savedAt,
        inLibrary = savedAt,
    )
}

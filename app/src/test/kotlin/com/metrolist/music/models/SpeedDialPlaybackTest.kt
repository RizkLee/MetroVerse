/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.models

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedDialPlaybackTest {
    private val episode = Song(
        SongEntity(
            id = "rss_episode_fixture", title = "Episode", duration = 1800,
            albumId = "rss_channel_fixture", albumName = "Channel",
            isEpisode = true, mediaUrl = "https://example.org/audio.mp3",
        ),
        artists = emptyList(),
    )

    @Test fun `history and quick pick tiles retain episode identity and duration`() {
        val tile = episode.toSpeedDialSongItem()
        assertTrue(tile.isEpisode)
        assertEquals(1800, tile.duration)
        assertEquals("rss_channel_fixture", tile.album?.id)
    }

    @Test fun `old pins without episode flags restore RSS metadata at playback`() = runBlocking {
        val oldPin = SongItem(id = episode.id, title = "Old tile", artists = emptyList(), thumbnail = "")
        val restored = resolveSpeedDialMetadata(oldPin) { episode }
        assertTrue(restored.isEpisode)
        assertEquals(episode.song.mediaUrl, restored.mediaUrl)
        assertEquals(episode.song.albumName, restored.album?.title)
        assertEquals(episode.song.duration, restored.duration)
    }

    @Test fun `stored YouTube episodes also bypass song playback without a direct media URL`() = runBlocking {
        val stored = episode.copy(song = episode.song.copy(id = "youtube-episode", mediaUrl = null))
        val tile = stored.toSpeedDialSongItem()
        val restored = resolveSpeedDialMetadata(tile) { stored }
        assertTrue(restored.isEpisode)
        assertNull(restored.mediaUrl)
    }

    @Test fun `ordinary music and unknown online tracks keep their original metadata`() = runBlocking {
        val item = SongItem(id = "music", title = "Online title", artists = emptyList(), thumbnail = "")
        val music = episode.copy(song = episode.song.copy(id = "music", isEpisode = false, mediaUrl = null))
        assertEquals(item.toMediaMetadata(), resolveSpeedDialMetadata(item) { music })
        assertEquals(item.toMediaMetadata(), resolveSpeedDialMetadata(item) { null })
    }
}

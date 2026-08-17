package com.metrolist.music.extensions

import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class PlaylistDeduplicationTest {
    @Test
    fun `duplicate browse ids keep the playlist with official artwork`() {
        val withoutArtwork = playlist(id = "without-artwork", browseId = "PL123", thumbnailUrl = null, songCount = 20)
        val withArtwork = playlist(id = "with-artwork", browseId = "PL123", thumbnailUrl = "https://example.com/cover.jpg", songCount = 5)

        val result = listOf(withoutArtwork, withArtwork).deduplicateByBrowseId()

        assertEquals(listOf("with-artwork"), result.map(Playlist::id))
    }

    @Test
    fun `local playlists without browse ids remain distinct`() {
        val first = playlist(id = "local-1", browseId = null, thumbnailUrl = null, songCount = 1)
        val second = playlist(id = "local-2", browseId = null, thumbnailUrl = null, songCount = 1)

        val result = listOf(first, second).deduplicateByBrowseId()

        assertEquals(listOf("local-1", "local-2"), result.map(Playlist::id))
    }

    private fun playlist(
        id: String,
        browseId: String?,
        thumbnailUrl: String?,
        songCount: Int,
    ) =
        Playlist(
            playlist =
                PlaylistEntity(
                    id = id,
                    name = id,
                    browseId = browseId,
                    thumbnailUrl = thumbnailUrl,
                    bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                ),
            songCount = songCount,
            songThumbnails = emptyList(),
        )
}

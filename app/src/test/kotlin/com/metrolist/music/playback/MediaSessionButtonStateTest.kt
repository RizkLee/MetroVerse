package com.metrolist.music.playback

import androidx.media3.common.Player
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionButtonStateTest {
    private val savedAt = LocalDateTime.of(2026, 10, 8, 12, 0)
    private val episode = SongEntity(id = "episode", title = "Episode", isEpisode = true)
    private val track = SongEntity(id = "track", title = "Track")

    @Test
    fun `periodic progress checkpoints do not refresh episode controls`() {
        val before = state(episode)
        val after = state(
            episode.copy(
                playbackPosition = 15_000L,
                totalPlayTime = 15_000L,
                duration = 3_600,
                dateModified = savedAt,
            ),
        )

        assertEquals(before, after)
    }

    @Test
    fun `episode saving changes controls without using the music like flag`() {
        val before = state(episode)
        assertEquals(before, state(episode.copy(liked = true)))

        val saved = state(episode.copy(inLibrary = savedAt))
        assertNotEquals(before, saved)
        assertTrue(saved.isSavedOrLiked)
    }

    @Test
    fun `episode controls ignore music shuffle and repeat settings`() {
        assertEquals(
            state(episode),
            state(episode, repeatMode = Player.REPEAT_MODE_ALL, shuffleModeEnabled = true),
        )
    }

    @Test
    fun `music like repeat and shuffle changes each refresh controls`() {
        val before = state(track)
        assertNotEquals(before, state(track.copy(liked = true)))
        assertNotEquals(before, state(track, repeatMode = Player.REPEAT_MODE_ONE))
        assertNotEquals(before, state(track, shuffleModeEnabled = true))
        assertEquals(before, state(track.copy(playbackPosition = 15_000L, totalPlayTime = 15_000L)))
    }

    @Test
    fun `previous song record cannot enable or mark new item as saved`() {
        val pending = mediaSessionButtonState(
            mediaId = "new-episode",
            isEpisode = true,
            song = episode.copy(inLibrary = savedAt),
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleModeEnabled = false,
        )

        assertEquals("new-episode", pending.mediaId)
        assertFalse(pending.hasCurrentSong)
        assertFalse(pending.isSavedOrLiked)
        assertNotEquals(pending, state(episode.copy(id = "new-episode", inLibrary = savedAt)))
    }

    @Test
    fun `optimistic like state matches its later database update`() {
        val optimistic = mediaSessionButtonState(
            mediaId = track.id,
            isEpisode = false,
            song = track,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleModeEnabled = false,
            isLikedOverride = true,
        )

        assertEquals(optimistic, state(track.copy(liked = true)))
        val optimisticRemoval = mediaSessionButtonState(
            mediaId = track.id,
            isEpisode = false,
            song = track.copy(liked = true),
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleModeEnabled = false,
            isLikedOverride = false,
        )
        assertEquals(optimisticRemoval, state(track))
    }

    @Test
    fun `clearing player state clears current song controls`() {
        val cleared = mediaSessionButtonState(
            mediaId = null,
            isEpisode = false,
            song = track.copy(liked = true),
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleModeEnabled = false,
        )

        assertFalse(cleared.hasCurrentSong)
        assertFalse(cleared.isSavedOrLiked)
        assertNotEquals(state(track), cleared)
    }

    private fun state(
        song: SongEntity,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        shuffleModeEnabled: Boolean = false,
    ) = mediaSessionButtonState(
        mediaId = song.id,
        isEpisode = song.isEpisode,
        song = song,
        repeatMode = repeatMode,
        shuffleModeEnabled = shuffleModeEnabled,
    )
}

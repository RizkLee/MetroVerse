/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import androidx.media3.common.C
import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleManagerTest {
    private fun song(
        id: String = "song1",
        title: String = "Song One",
        artist: String = "Artist One",
        durationSec: Int = 210,
        isEpisode: Boolean = false,
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = if (artist.isBlank()) emptyList() else listOf(MediaMetadata.Artist(id = "art1", name = artist)),
        duration = durationSec,
        isEpisode = isEpisode,
    )

    @Test
    fun effectiveDurationSeconds_ignoresUnsetAndNonPositivePlayerDuration() {
        val track = song(durationSec = 185)
        assertEquals(185, ScrobbleManager.effectiveDurationSeconds(track, C.TIME_UNSET))
        assertEquals(185, ScrobbleManager.effectiveDurationSeconds(track, -1L))
        assertEquals(185, ScrobbleManager.effectiveDurationSeconds(track, 0L))
        assertEquals(200, ScrobbleManager.effectiveDurationSeconds(track, 200_000L))
    }

    @Test
    fun shouldScrobbleTrack_excludesEpisodesAndShortTracks() {
        assertFalse(ScrobbleManager.shouldScrobbleTrack(song(isEpisode = true), durationSec = 600, minSongDuration = 30))
        assertFalse(ScrobbleManager.shouldScrobbleTrack(song(durationSec = 25), durationSec = 25, minSongDuration = 30))
        assertFalse(ScrobbleManager.shouldScrobbleTrack(song(artist = ""), durationSec = 200, minSongDuration = 30))
        assertTrue(ScrobbleManager.shouldScrobbleTrack(song(durationSec = 200), durationSec = 200, minSongDuration = 30))
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.models

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.Song

internal fun Song.toSpeedDialSongItem() = SongItem(
    id = id,
    title = title,
    artists = orderedArtists.map { Artist(name = it.name, id = it.id) },
    album = song.albumId?.let { com.metrolist.innertube.models.Album(song.albumName.orEmpty(), it) },
    duration = song.duration,
    thumbnail = thumbnailUrl.orEmpty(),
    explicit = song.explicit,
    isEpisode = song.isEpisode,
)

/** Old pins and lightweight tiles cannot carry RSS URLs; resolve the stored episode at playback. */
internal suspend fun resolveSpeedDialMetadata(
    item: SongItem,
    findSong: suspend (String) -> Song?,
): MediaMetadata {
    val stored = findSong(item.id)
    return if (stored?.song?.isEpisode == true) stored.toMediaMetadata() else item.toMediaMetadata()
}

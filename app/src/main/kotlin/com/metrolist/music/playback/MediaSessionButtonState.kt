/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.Player
import com.metrolist.music.db.entities.SongEntity

internal data class MediaSessionButtonState(
    val mediaId: String?,
    val isEpisode: Boolean,
    val hasCurrentSong: Boolean,
    val isSavedOrLiked: Boolean,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
)

internal fun mediaSessionButtonState(
    mediaId: String?,
    isEpisode: Boolean,
    song: SongEntity?,
    repeatMode: Int,
    shuffleModeEnabled: Boolean,
    isLikedOverride: Boolean? = null,
): MediaSessionButtonState {
    // Room may still hold the previous item briefly after a player transition.
    val currentSong = song?.takeIf { it.id == mediaId }
    return MediaSessionButtonState(
        mediaId = mediaId,
        isEpisode = isEpisode,
        hasCurrentSong = currentSong != null,
        isSavedOrLiked =
            currentSong != null &&
                (isLikedOverride ?: if (isEpisode) currentSong.inLibrary != null else currentSong.liked),
        repeatMode = if (isEpisode) Player.REPEAT_MODE_OFF else repeatMode,
        shuffleModeEnabled = !isEpisode && shuffleModeEnabled,
    )
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

/** A clicked line owns the scroll target while the player is still reporting its old position. */
internal data class LyricsSeekTarget(
    val index: Int,
    val positionMs: Long,
    val requestedAtMs: Long,
) {
    fun isPending(playbackIndex: Int, nowMs: Long): Boolean =
        playbackIndex != index && nowMs - requestedAtMs < 2_000L
}

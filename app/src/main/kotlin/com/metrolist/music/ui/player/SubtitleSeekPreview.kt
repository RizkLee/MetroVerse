/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

/** Keeps the tapped cue visible while the player acknowledges an asynchronous seek. */
internal class SubtitleSeekPreview {
    private var targetIndex: Int? = null
    private var requestedAtMs = 0L

    fun request(index: Int, nowMs: Long) {
        targetIndex = index
        requestedAtMs = nowMs
    }

    fun index(playbackIndex: Int, cueCount: Int, nowMs: Long): Int {
        val target = targetIndex ?: return playbackIndex
        if (playbackIndex == target || target !in 0 until cueCount || nowMs - requestedAtMs >= 4_000) {
            targetIndex = null
            return playbackIndex
        }
        return target
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

/** Keeps the tapped cue visible while the player acknowledges an asynchronous seek. */
internal class SubtitleSeekPreview {
    private var targetIndex: Int? = null
    private var targetKey: String? = null
    private var requestedAtMs = 0L

    fun request(index: Int, nowMs: Long, cueKey: String? = null) {
        targetIndex = index
        targetKey = cueKey
        requestedAtMs = nowMs
    }

    fun index(
        playbackIndex: Int,
        cueCount: Int,
        nowMs: Long,
        cueIndexForKey: ((String) -> Int)? = null,
    ): Int {
        val target = targetKey?.let { cueIndexForKey?.invoke(it) } ?: targetIndex ?: return playbackIndex
        if (playbackIndex == target || target !in 0 until cueCount || nowMs - requestedAtMs >= 4_000) {
            targetIndex = null
            targetKey = null
            return playbackIndex
        }
        return target
    }
}

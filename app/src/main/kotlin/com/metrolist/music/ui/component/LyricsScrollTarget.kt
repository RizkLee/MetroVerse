/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils.findActiveLineIndices

internal data class LyricsScrollTargetState(
    val index: Int = -1,
    val lastMainIndex: Int = -1,
    val previousActiveIndices: Set<Int> = emptySet(),
)

internal fun resolveLyricsScrollTarget(
    state: LyricsScrollTargetState,
    activeIndices: Set<Int>,
    mainLineIndices: Set<Int>,
    previousPlaybackPositionMs: Long,
    playbackPositionMs: Long,
    isSeeking: Boolean,
    pendingSeekIndex: Int? = null,
    fallbackAnchorIndex: Int = -1,
): LyricsScrollTargetState {
    val activeMainMax = activeIndices.filter { it in mainLineIndices }.maxOrNull()
        ?: activeIndices.maxOrNull() ?: -1
    // A scrub preview can start and finish between frames. The player's actual rollback
    // must reset the forward-only anchor and its remembered main line as well.
    if (isSeeking || playbackPositionMs < previousPlaybackPositionMs) {
        val target = pendingSeekIndex ?: activeMainMax.takeIf { it >= 0 } ?: fallbackAnchorIndex
        return LyricsScrollTargetState(target, target, activeIndices)
    }

    val targetStillActive = state.index in activeIndices
    val anyActive = activeIndices.isNotEmpty()
    val shouldAdvance = when {
        !targetStillActive && anyActive && activeMainMax > state.index -> true
        !targetStillActive && !anyActive && state.previousActiveIndices.isNotEmpty() -> true
        state.index == -1 && anyActive -> true
        state.previousActiveIndices.isEmpty() && anyActive && activeMainMax > state.index -> true
        else -> false
    }
    val nextTarget = if (shouldAdvance) {
        if (anyActive) activeMainMax else mainLineIndices.filter { it > state.lastMainIndex }.minOrNull() ?: state.index
    } else {
        state.index
    }
    return LyricsScrollTargetState(
        index = maxOf(state.index, nextTarget),
        lastMainIndex = maxOf(state.lastMainIndex, activeMainMax),
        previousActiveIndices = activeIndices,
    )
}

/** Blank LRC timestamps have no row unless a long-gap indicator was created. */
internal fun resolveLyricsListAnchorIndex(items: List<LyricsListItem>, lyricIndex: Int): Int {
    if (items.isEmpty()) return -1
    val exact = items.indexOfFirst { item ->
        when (item) {
            is LyricsListItem.Line -> item.index == lyricIndex
            is LyricsListItem.Indicator -> item.afterLineIndex == lyricIndex
        }
    }
    if (exact >= 0) return exact
    return items.indexOfLast { item ->
        when (item) {
            is LyricsListItem.Line -> item.index < lyricIndex
            is LyricsListItem.Indicator -> item.afterLineIndex < lyricIndex
        }
    }.coerceAtLeast(0)
}

internal fun findLyricsScrollAnchor(lines: List<LyricsEntry>, positionMs: Long): Int {
    val active = findActiveLineIndices(lines, positionMs)
    return active.filter { !lines[it].isBackground }.maxOrNull()
        ?: active.maxOrNull()?.let { index ->
            (index downTo 0).firstOrNull { !lines[it].isBackground } ?: index
        }
        ?: lines.indexOfLast { !it.isBackground && it.time <= positionMs }.takeIf { it >= 0 }
        ?: lines.indexOfFirst { !it.isBackground }
}

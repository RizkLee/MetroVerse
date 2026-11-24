/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils.findActiveLineIndices

internal const val LYRICS_SCROLL_LOOK_AHEAD_WORD_MS = 450L
internal const val LYRICS_SCROLL_LOOK_AHEAD_LINE_MS = 400L
internal const val LYRICS_SCROLL_ANIMATION_DURATION_MS = 500
internal const val LYRICS_STAGGER_DELAY_PER_DISTANCE_MS = 20
internal const val LYRICS_STAGGER_DELAY_MAX_MS = 160

/**
 * Computes how early the viewport should begin scrolling toward the next lyric line while
 * keeping word highlighting strictly locked to the real playback position.
 */
internal fun computeLyricsScrollLookAheadMs(
    lines: List<LyricsEntry>,
    currentAnchorIndex: Int,
    hasWordTimings: Boolean,
): Long {
    if (!hasWordTimings) return LYRICS_SCROLL_LOOK_AHEAD_LINE_MS
    val current = lines.getOrNull(currentAnchorIndex) ?: return LYRICS_SCROLL_LOOK_AHEAD_WORD_MS
    val nextMain =
        (currentAnchorIndex + 1 until lines.size)
            .firstOrNull { !lines[it].isBackground && lines[it].text.isNotBlank() }
            ?.let { lines[it] }
    val endMs =
        nextMain?.time
            ?: current.words?.lastOrNull()?.let { (it.endTime * 1000).toLong() }
            ?: return LYRICS_SCROLL_LOOK_AHEAD_WORD_MS
    val durationMs = (endMs - current.time).coerceAtLeast(0L)
    return if (durationMs in 1L..899L) {
        (durationMs / 2L).coerceAtLeast(150L)
    } else {
        LYRICS_SCROLL_LOOK_AHEAD_WORD_MS
    }
}

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
    advanceDuringGap: Boolean = true,
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
        !targetStillActive && !anyActive && state.previousActiveIndices.isNotEmpty() && advanceDuringGap -> true
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
            is LyricsListItem.Provider -> false
        }
    }
    if (exact >= 0) return exact
    return items.indexOfLast { item ->
        when (item) {
            is LyricsListItem.Line -> item.index < lyricIndex
            is LyricsListItem.Indicator -> item.afterLineIndex < lyricIndex
            is LyricsListItem.Provider -> false
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

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSeekTargetTest {
    @Test
    fun `ordinary playback cannot clear a click while its old position is still reported`() {
        val target = LyricsSeekTarget(12, 60_000, 1_000)
        val waiting = resolveLyricsPlaybackPosition(12_000, null, 3, target, 1_100)
        assertEquals(60_000, waiting.positionMs)
        assertEquals(target, waiting.pendingSeek)
        assertTrue(waiting.isSeeking)
    }

    @Test
    fun `paused seek uses the player confirmation without waiting for playback to resume`() {
        val target = LyricsSeekTarget(12, 60_000, 1_000)
        val confirmed = resolveLyricsPlaybackPosition(60_000, null, 12, target, 1_100)
        assertEquals(60_000, confirmed.positionMs)
        assertNull(confirmed.pendingSeek)
        assertFalse(confirmed.isSeeking)
        val paused = resolveLyricsPlaybackPosition(60_000, null, 12, confirmed.pendingSeek, 20_000)
        assertEquals(60_000, paused.positionMs)
        assertFalse(paused.isSeeking)
    }

    @Test
    fun `an actual scrub takes ownership from an earlier clicked line`() {
        val preview = resolveLyricsPlaybackPosition(12_000, 90_000, 3, LyricsSeekTarget(12, 60_000, 1_000), 1_100)
        assertEquals(90_000, preview.positionMs)
        assertNull(preview.pendingSeek)
        assertTrue(preview.isSeeking)
    }

    @Test
    fun `old playback indices cannot reclaim a clicked target before confirmation`() {
        val target = LyricsSeekTarget(index = 12, positionMs = 60_000L, requestedAtMs = 1_000L)

        assertTrue(target.isPending(playbackIndex = 3, nowMs = 1_100L))
        assertTrue(target.isPending(playbackIndex = 18, nowMs = 1_800L))
        assertFalse(target.isPending(playbackIndex = 12, nowMs = 1_900L))
    }

    @Test
    fun `a failed seek eventually releases the scroll target`() {
        val target = LyricsSeekTarget(index = 0, positionMs = 0L, requestedAtMs = 1_000L)

        assertTrue(target.isPending(playbackIndex = 15, nowMs = 2_999L))
        assertFalse(target.isPending(playbackIndex = 15, nowMs = 3_000L))
    }

    @Test
    fun `a delayed confirmation of the previous click cannot replace the latest click`() {
        val first = LyricsSeekTarget(index = 20, positionMs = 90_000L, requestedAtMs = 1_000L)
        val latest = LyricsSeekTarget(index = 4, positionMs = 12_000L, requestedAtMs = 1_400L)

        assertTrue(latest.isPending(playbackIndex = first.index, nowMs = 1_600L))
        assertTrue(latest.isPending(playbackIndex = first.index, nowMs = 3_100L))
        assertFalse(latest.isPending(playbackIndex = latest.index, nowMs = 3_150L))
    }

    @Test
    fun `lyric tap seek does not extrapolate forward while player clock waits to advance`() {
        val initialState = LyricsPlaybackClockState(lastRawPlayerPosMs = 12_000L, lastSampleWallMs = 1_000L)

        // Frame immediately after lyric tap: player reports target 60_000ms before AudioTrack outputs sound.
        val firstSample =
            sampleLyricsObservedPosition(
                state = initialState,
                rawPlayerPosMs = 60_000L,
                sliderPositionMs = null,
                nowMs = 1_050L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = false,
                hasPendingSeek = true,
                seekTriggered = true,
            )
        assertEquals(60_000L, firstSample.observedPositionMs)
        assertTrue(firstSample.clockState.awaitingAdvanceAfterSeek)

        // Even 500ms later while raw player position remains pinned at 60_000ms, word animation stays still.
        val stalledSample =
            sampleLyricsObservedPosition(
                state = firstSample.clockState,
                rawPlayerPosMs = 60_000L,
                sliderPositionMs = null,
                nowMs = 1_550L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = false,
                hasPendingSeek = false,
                seekTriggered = false,
            )
        assertEquals(60_000L, stalledSample.observedPositionMs)
        assertTrue(stalledSample.clockState.awaitingAdvanceAfterSeek)

        // Once audio output starts and currentPosition genuinely advances, lockstep interpolation resumes (<= 32ms cap).
        val flowingSample =
            sampleLyricsObservedPosition(
                state = stalledSample.clockState,
                rawPlayerPosMs = 60_016L,
                sliderPositionMs = null,
                nowMs = 1_566L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = false,
                hasPendingSeek = false,
                seekTriggered = false,
            )
        assertEquals(60_016L, flowingSample.observedPositionMs)
        assertFalse(flowingSample.clockState.awaitingAdvanceAfterSeek)

        val cappedNextFrame =
            sampleLyricsObservedPosition(
                state = flowingSample.clockState,
                rawPlayerPosMs = 60_016L,
                sliderPositionMs = null,
                nowMs = 1_966L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = false,
                hasPendingSeek = false,
                seekTriggered = false,
            )
        assertEquals(60_016L + LOCAL_LYRICS_EXTRAPOLATION_MAX_MS, cappedNextFrame.observedPositionMs)
    }

    @Test
    fun `re-tapping active line confirms only after player reaches line start window`() {
        val target = LyricsSeekTarget(index = 12, positionMs = 60_000L, requestedAtMs = 1_000L)

        // Player is still at 61_200ms within the same line 12: must NOT prematurely confirm.
        assertFalse(
            isLyricsSeekConfirmed(
                observedPositionMs = 61_200L,
                target = target,
                isBuffering = false,
                activeIndicesAtObserved = setOf(12),
            ),
        )
        // Buffering at target must also wait.
        assertFalse(
            isLyricsSeekConfirmed(
                observedPositionMs = 60_000L,
                target = target,
                isBuffering = true,
                activeIndicesAtObserved = setOf(12),
            ),
        )
        // Player landed slightly before line start at audio frame boundary (59_985ms): confirms cleanly.
        assertTrue(
            isLyricsSeekConfirmed(
                observedPositionMs = 59_985L,
                target = target,
                isBuffering = false,
                activeIndicesAtObserved = emptySet(),
            ),
        )
    }

    @Test
    fun `cast playback retains bounded interpolation up to cast ceiling and handles 500ms poll steps`() {
        val state = LyricsPlaybackClockState(lastRawPlayerPosMs = 40_000L, lastSampleWallMs = 2_000L)
        val result =
            sampleLyricsObservedPosition(
                state = state,
                rawPlayerPosMs = 40_000L,
                sliderPositionMs = null,
                nowMs = 2_600L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = true,
                hasPendingSeek = false,
                seekTriggered = false,
            )
        assertEquals(40_600L, result.observedPositionMs)
        assertFalse(result.clockState.awaitingAdvanceAfterSeek)

        // Real Cast 500ms polling often wakes up slightly above 500ms (e.g., 520ms) and must NOT reset interpolation.
        val pollStep =
            sampleLyricsObservedPosition(
                state = result.clockState,
                rawPlayerPosMs = 40_520L,
                sliderPositionMs = null,
                nowMs = 2_620L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = true,
                hasPendingSeek = false,
                seekTriggered = false,
            )
        assertFalse(pollStep.clockState.awaitingAdvanceAfterSeek)
        assertEquals(40_520L, pollStep.observedPositionMs)

        // Between that 520ms poll and the next poll, bounded Cast interpolation continues smoothly.
        val betweenPolls =
            sampleLyricsObservedPosition(
                state = pollStep.clockState,
                rawPlayerPosMs = 40_520L,
                sliderPositionMs = null,
                nowMs = 2_900L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = true,
                hasPendingSeek = false,
                seekTriggered = false,
            )
        assertEquals(40_800L, betweenPolls.observedPositionMs)

        // After a Cast lyric-tap seek, awaitingAdvanceAfterSeek clears cleanly on the first ~520ms Cast poll advance.
        val castSeek =
            sampleLyricsObservedPosition(
                state = betweenPolls.clockState,
                rawPlayerPosMs = 80_000L,
                sliderPositionMs = null,
                nowMs = 3_000L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = true,
                hasPendingSeek = false,
                seekTriggered = true,
            )
        assertTrue(castSeek.clockState.awaitingAdvanceAfterSeek)

        val castSeekResumed =
            sampleLyricsObservedPosition(
                state = castSeek.clockState,
                rawPlayerPosMs = 80_520L,
                sliderPositionMs = null,
                nowMs = 3_520L,
                playbackSpeed = 1f,
                isActivelyPlaying = true,
                isBuffering = false,
                isCasting = true,
                hasPendingSeek = false,
                seekTriggered = false,
            )
        assertFalse(castSeekResumed.clockState.awaitingAdvanceAfterSeek)
    }
}

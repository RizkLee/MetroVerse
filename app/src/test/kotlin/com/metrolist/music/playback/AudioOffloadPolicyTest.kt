/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOffloadPolicyTest {
    @Test
    fun `audio offload eligibility requires preference enabled and no conflicting DSP or errors`() {
        val eligible = AudioOffloadEligibility(
            offloadPref = true,
            crossfadeEnabled = false,
            skipSilenceEnabled = false,
            normalizationActive = false,
            equalizerActive = false,
            speed = 1f,
            pitch = 1f,
            sinkErrored = false,
        )
        assertTrue(eligible.canOffload)
        assertFalse(eligible.copy(offloadPref = false).canOffload)
        assertFalse(eligible.copy(crossfadeEnabled = true).canOffload)
        assertFalse(eligible.copy(skipSilenceEnabled = true).canOffload)
        assertFalse(eligible.copy(normalizationActive = true).canOffload)
        assertFalse(eligible.copy(equalizerActive = true).canOffload)
        assertFalse(eligible.copy(speed = 1.25f).canOffload)
        assertFalse(eligible.copy(pitch = 0.9f).canOffload)
        assertFalse(eligible.copy(sinkErrored = true).canOffload)
    }

    @Test
    fun `offload sleep stall tracker triggers after timeout without output progress`() {
        val tracker = OffloadSleepStallTracker(timeoutMs = 2_000L)

        assertFalse(tracker.shouldDisableOffload(outputPosition = 1_000L, expectProgress = true, nowMs = 5_000L))

        tracker.onSleepingForOffload(nowMs = 1_000L, outputPosition = 10_000L)
        assertTrue(tracker.isSleeping())

        // Output advancing resets stall clock
        assertFalse(tracker.shouldDisableOffload(outputPosition = 11_000L, expectProgress = true, nowMs = 2_500L))
        // Paused state never false-triggers stall
        assertFalse(tracker.shouldDisableOffload(outputPosition = 11_000L, expectProgress = false, nowMs = 6_000L))
        // Once playing resumes expecting progress, full 2s timeout is measured cleanly
        assertFalse(tracker.shouldDisableOffload(outputPosition = 11_000L, expectProgress = true, nowMs = 7_500L))
        assertFalse(tracker.shouldDisableOffload(outputPosition = 11_000L, expectProgress = true, nowMs = 9_499L))
        assertTrue(tracker.shouldDisableOffload(outputPosition = 11_000L, expectProgress = true, nowMs = 9_500L))
        assertFalse(tracker.isSleeping())
    }
}

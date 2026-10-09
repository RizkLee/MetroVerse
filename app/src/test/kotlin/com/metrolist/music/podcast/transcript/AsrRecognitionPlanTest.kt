/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrRecognitionPlanTest {
    @Test
    fun `abandoned short audio without returned lines stays missing because VAD may still buffer speech`() {
        assertEquals(100_000, finalizedAsrBoundary(100_000, 110_000, emptyList()))
        assertEquals(104_000, finalizedAsrBoundary(100_000, 110_000, listOf(SubtitleCue(100_000, 104_000, "final"))))
        assertEquals(100_000, finalizedAsrBoundary(100_000, 110_000, listOf(SubtitleCue(92_000, 98_000, "context only"))))
    }

    @Test(expected = AsrRecordingChangedException::class)
    fun `a shorter reopened recording cannot be completed with distant saved subtitles`() {
        verifyAsrMediaEnd(120_000, listOf(AsrAudioRange(600_000, 900_000)), listOf(SubtitleCue(890_000, 895_000, "old recording")))
    }

    @Test
    fun `small codec tail differences do not discard valid saved work`() {
        verifyAsrMediaEnd(900_000, listOf(AsrAudioRange(600_000, 900_001)), emptyList())
    }

    @Test
    fun `repeated skip presses are one settled jump with the original origin`() {
        var now = 0L
        val requests = AsrSeekRequests { now }
        for (index in 0 until 15) {
            requests.offer(index * 10_000L, (index + 1) * 10_000L)
            now += 100
            assertNull(requests.poll())
        }
        now += 1_900
        assertEquals(AsrSeekTarget(0, 150_000), requests.poll())
        assertNull(requests.poll())
    }

    @Test
    fun `scrub back to its initial position does not reprioritize or leave stale work`() {
        var now = 0L
        val requests = AsrSeekRequests { now }
        requests.offer(20_000, 500_000)
        now = 500
        requests.offer(500_000, 20_000)
        now = 2_500
        val final = requests.poll()!!
        assertFalse(shouldPrioritizeAsrSeek(final, 0, 40_000, emptyList()))
        requests.offer(20_000, 300_000)
        requests.clear()
        now += 5_000
        assertNull(requests.poll())
    }

    @Test
    fun `starting at three quarters reports no work done then fills the earlier gap`() {
        val plan = AsrRecognitionPlan()
        assertEquals(AsrWorkRange(750_000, null), plan.next(750_000))
        assertEquals(0, plan.progress(1_000_000))
        plan.record(750_000, 1_000_000)
        plan.reachedEnd(1_000_000)
        assertEquals(25, plan.progress(1_000_000))
        assertEquals(AsrWorkRange(0, 750_000), plan.next())
        plan.record(0, 750_000)
        assertNull(plan.next())
    }

    @Test
    fun `backward seek fills the new gap then returns to interrupted forward work`() {
        val plan = AsrRecognitionPlan(listOf(AsrAudioRange(600_000, 900_000)))
        plan.defer(900_000)
        assertEquals(AsrWorkRange(300_000, 600_000), plan.next(300_000))
        plan.record(300_000, 600_000)
        assertEquals(AsrWorkRange(900_000, null), plan.next())
    }

    @Test
    fun `large forward seek does not lose the skipped interval or count it as complete`() {
        val plan = AsrRecognitionPlan(listOf(AsrAudioRange(0, 60_000)))
        plan.defer(60_000)
        assertEquals(AsrWorkRange(600_000, null), plan.next(600_000))
        plan.record(600_000, 900_000)
        plan.reachedEnd(900_000)
        assertEquals(40, plan.progress(900_000))
        assertEquals(AsrWorkRange(60_000, 600_000), plan.next())
    }

    @Test
    fun `small forward jumps including two minutes retain the recognizer`() {
        assertFalse(shouldPrioritizeAsrSeek(AsrSeekTarget(100_000, 220_000), 0, 120_000, emptyList()))
        assertTrue(shouldPrioritizeAsrSeek(AsrSeekTarget(100_000, 220_001), 0, 120_000, emptyList()))
    }

    @Test
    fun `seeks inside saved or active audio do not restart inference`() {
        val saved = listOf(AsrAudioRange(600_000, 900_000))
        assertFalse(shouldPrioritizeAsrSeek(AsrSeekTarget(0, 700_000), 100_000, 200_000, saved))
        assertFalse(shouldPrioritizeAsrSeek(AsrSeekTarget(600_000, 150_000), 100_000, 200_000, saved))
        assertTrue(shouldPrioritizeAsrSeek(AsrSeekTarget(600_000, 50_000), 100_000, 200_000, saved))
    }

    @Test
    fun `shorter metadata cannot truncate unknown audio end`() {
        val plan = AsrRecognitionPlan()
        plan.record(0, 1_200_000)
        assertEquals(99, plan.progress(1_000_000))
        assertEquals(AsrWorkRange(1_200_000, null), plan.next())
        plan.reachedEnd(1_220_000)
        assertEquals(AsrWorkRange(1_200_000, 1_220_000), plan.next())
    }

    @Test
    fun `overlapping ranges count once and silence may close a gap without text`() {
        val plan = AsrRecognitionPlan(listOf(AsrAudioRange(0, 10_000), AsrAudioRange(5_000, 20_000)))
        plan.record(20_000, 30_000)
        assertEquals(listOf(AsrAudioRange(0, 30_000)), plan.processedRanges)
        assertEquals(50, plan.progress(60_000))
    }

    @Test
    fun `out of range preferred positions fall back to real missing work`() {
        val plan = AsrRecognitionPlan(listOf(AsrAudioRange(0, 10_000)), 30_000)
        assertEquals(AsrWorkRange(10_000, 30_000), plan.next(90_000))
        plan.reachedEnd(0)
        assertNull(plan.next())
    }

    @Test
    fun `inserting an earlier segment keeps later timestamps and text intact`() {
        val later = SubtitleCue(600_000, 605_000, "Already translated")
        val earlier = SubtitleCue(50_000, 55_000, "Earlier speech")
        assertEquals(listOf(earlier, later), replaceAsrRangeCues(listOf(later), listOf(earlier), 0, 600_000))
    }

    @Test
    fun `replacement discards stale text only inside its owned media range`() {
        val before = SubtitleCue(0, 1_000, "before")
        val after = SubtitleCue(20_000, 21_000, "after")
        val old = SubtitleCue(5_000, 9_000, "old")
        val overlapping = SubtitleCue(3_000, 12_000, "new")
        assertEquals(
            listOf(before, SubtitleCue(5_000, 10_000, "new"), after),
            replaceAsrRangeCues(listOf(before, old, after), listOf(overlapping), 5_000, 10_000),
        )
    }
}

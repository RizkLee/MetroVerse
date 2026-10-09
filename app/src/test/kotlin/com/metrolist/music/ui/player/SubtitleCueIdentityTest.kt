/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import com.metrolist.music.podcast.transcript.SubtitleCue
import com.metrolist.music.podcast.transcript.subtitleCueFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleCueIdentityTest {
    @Test
    fun `backfilled cues do not change existing viewport keys`() {
        val cache = SubtitleCueIdentityCache()
        val later = cue(20_000L, "Later")
        val last = cue(30_000L, "Last")
        val original = cache.keyedCues(listOf(later, last))
        val updated = cache.keyedCues(listOf(cue(10_000L, "Earlier"), later.copy(), last.copy()))

        assertEquals(original.map { it.key }, updated.drop(1).map { it.key })
        assertEquals(original.map { it.key }, SubtitleCueIdentityCache().keyedCues(listOf(later, last)).map { it.key })
    }

    @Test
    fun `identical duplicate cues receive distinct stable keys`() {
        val cache = SubtitleCueIdentityCache()
        val repeated = cue(20_000L, "Repeated")
        val original = cache.keyedCues(listOf(repeated, repeated.copy(), repeated))
        val updated = cache.keyedCues(listOf(cue(10_000L, "Earlier"), repeated, repeated.copy(), repeated))

        assertEquals(3, original.map { it.key }.toSet().size)
        assertEquals(original.map { it.key }, updated.drop(1).map { it.key })
    }

    @Test
    fun `timing and text corrections get new identities`() {
        val original = cue(20_000L, "Original")
        val items = SubtitleCueIdentityCache().keyedCues(
            listOf(original, original.copy(text = "Corrected"), original.copy(endMs = 22_000L)),
        )

        assertNotEquals(items[0].key, items[1].key)
        assertNotEquals(items[0].key, items[2].key)
    }

    @Test
    fun `streaming updates hash only new cue values`() {
        val fingerprinted = mutableListOf<SubtitleCue>()
        val cache = SubtitleCueIdentityCache { value ->
            fingerprinted += value
            subtitleCueFingerprint(value)
        }
        val later = cue(20_000L, "Later")
        val earlier = cue(10_000L, "Earlier")
        cache.keyedCues(listOf(later, later.copy()))
        cache.keyedCues(listOf(earlier, later.copy(), later))

        assertEquals(listOf(later, earlier), fingerprinted)
    }

    @Test
    fun `removed cues are evicted without rehashing retained cues`() {
        val fingerprinted = mutableListOf<SubtitleCue>()
        val cache = SubtitleCueIdentityCache { value ->
            fingerprinted += value
            subtitleCueFingerprint(value)
        }
        val first = cue(10_000L, "First")
        val second = cue(20_000L, "Second")
        cache.keyedCues(listOf(first, second))
        cache.keyedCues(listOf(second.copy()))
        cache.keyedCues(listOf(first.copy(), second.copy()))

        assertEquals(listOf(first, second, first), fingerprinted)
    }

    @Test
    fun `backfilling keeps a selected duplicate separate from other occurrences`() {
        val cache = SubtitleCueIdentityCache()
        val repeated = cue(20_000L, "Repeated")
        val original = cache.keyedCues(listOf(repeated, repeated.copy()))
        val selected = setOf(original[1].key)
        val updated = cache.keyedCues(listOf(cue(10_000L, "Earlier"), repeated, repeated.copy()))

        assertEquals(selected, retainSubtitleCueSelection(updated, selected))
        assertEquals(updated[2].key, selected.single())
        assertEquals(listOf(repeated), selectedSubtitleCues(updated, selected))
    }

    @Test
    fun `replacing a selected cue does not select its new index occupant`() {
        val cache = SubtitleCueIdentityCache()
        val first = cue(10_000L, "First")
        val second = cue(20_000L, "Second")
        val original = cache.keyedCues(listOf(first, second))
        val selected = original.mapTo(mutableSetOf()) { it.key }
        val updated = cache.keyedCues(listOf(cue(5_000L, "Replacement"), second.copy()))

        assertEquals(setOf(original[1].key), retainSubtitleCueSelection(updated, selected))
        assertEquals(listOf(second), selectedSubtitleCues(updated, selected))
    }

    @Test
    fun `sharing follows cue time instead of selection order`() {
        val first = cue(10_000L, "First")
        val second = cue(20_000L, "Second")
        val third = cue(30_000L, "Third")
        val items = SubtitleCueIdentityCache().keyedCues(listOf(third, first, second))
        val selected = linkedSetOf(items[0].key, items[2].key, items[1].key)

        assertEquals(listOf(first, second, third), selectedSubtitleCues(items, selected))
    }

    private fun cue(startMs: Long, text: String) = SubtitleCue(startMs, startMs + 1_000L, text)
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import com.metrolist.music.lyrics.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsHighlightWordsTest {
    @Test
    fun `a held hyphenated word keeps its text and complete timing interval`() {
        val word = WordTimestamp("ha-ha-ha", 5.0, 8.0, hasTrailingSpace = true)

        val highlights = splitLyricsHighlightWords(listOf(word))

        assertEquals(listOf("ha-", "ha-", "ha"), highlights.map { it.text })
        assertEquals(listOf(5.0, 6.0, 7.0), highlights.map { it.startTime })
        assertEquals(listOf(6.0, 7.0, 8.0), highlights.map { it.endTime })
        assertFalse(highlights[0].hasTrailingSpace)
        assertFalse(highlights[1].hasTrailingSpace)
        assertTrue(highlights[2].hasTrailingSpace)
        assertEquals(displayText(listOf(word)), displayText(highlights))
    }

    @Test
    fun `joined syllables retain the spaces around neighbouring words`() {
        val words = listOf(
            WordTimestamp("go", 0.0, 1.0),
            WordTimestamp("back-to-", 1.0, 3.0, hasTrailingSpace = false),
            WordTimestamp("back", 3.0, 4.0, hasTrailingSpace = false),
        )

        val highlights = splitLyricsHighlightWords(words)

        assertEquals("go back-to-back", displayText(highlights))
        assertEquals(words.first(), highlights.first())
        assertEquals(words.last(), highlights.last())
        assertEquals(3.0, highlights[highlights.lastIndex - 1].endTime, 0.0)
    }

    @Test
    fun `literal repeated and edge hyphens never disappear or gain whitespace`() {
        for (text in listOf("-", "--", "-hold-", "re--start-", "🎵-愛", "(stay-true)")) {
            val word = WordTimestamp(text, 1.0, 5.0, hasTrailingSpace = false)
            val highlights = splitLyricsHighlightWords(listOf(word))

            assertEquals(text, displayText(highlights))
            assertTrue(highlights.all { it.text.isNotEmpty() && !it.hasTrailingSpace })
            assertEquals(word.startTime, highlights.first().startTime, 1e-9)
            assertEquals(word.endTime, highlights.last().endTime, 1e-9)
            highlights.zipWithNext().forEach { (before, after) ->
                assertEquals(before.endTime, after.startTime, 1e-9)
            }
        }
    }

    @Test
    fun `an ordinary hyphenated word keeps its provider timing among other words`() {
        val words = listOf(
            WordTimestamp("a", 0.0, 1.0),
            WordTimestamp("well-known", 1.0, 3.0),
            WordTimestamp("song", 3.0, 4.0, hasTrailingSpace = false),
        )

        assertEquals(words, splitLyricsHighlightWords(words))
    }

    private fun displayText(words: List<WordTimestamp>): String =
        words.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
}

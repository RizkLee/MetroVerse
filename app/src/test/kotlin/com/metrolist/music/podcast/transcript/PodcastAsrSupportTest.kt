/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastAsrSupportTest {
    @Test
    fun `RSS episodes with direct audio retain on-device recognition`() {
        assertTrue(PodcastAsrSupport.supportsOnDeviceTranscription(true, "https://example.com/episode.mp3"))
    }

    @Test
    fun `YouTube episodes and missing audio sources cannot start recognition`() {
        listOf(null, "", "  ").forEach { source ->
            assertFalse(PodcastAsrSupport.supportsOnDeviceTranscription(true, source))
        }
    }

    @Test
    fun `a music item cannot become an ASR episode merely by having an audio URL`() {
        assertFalse(PodcastAsrSupport.supportsOnDeviceTranscription(false, "https://example.com/song.mp3"))
    }

    @Test
    fun `splitLongCue keeps short cue untouched`() {
        val cue = SubtitleCue(0, 5_000, "Hello world")
        val split = PodcastAsrSupport.splitLongCue(cue)

        assertEquals(1, split.size)
        assertEquals(cue, split[0])
    }

    @Test
    fun `splitLongCue splits long cue proportionally in time`() {
        val text = (1..40).joinToString(" ") { "word$it" }
        val cue = SubtitleCue(0, 40_000, text)
        val split = PodcastAsrSupport.splitLongCue(cue)

        assertTrue(split.size >= 3)
        assertEquals(0L, split.first().startMs)
        assertEquals(40_000L, split.last().endMs)
        // Time coverage must be contiguous.
        for (i in 1 until split.size) {
            assertEquals(split[i - 1].endMs, split[i].startMs)
        }
        // All words preserved.
        assertEquals(text, split.joinToString(" ") { it.text })
    }

    @Test
    fun `Mandarin cues without spaces are split without dropping text or breaking Unicode`() {
        val text = "这是一段普通话播客字幕测试，包含汉字𠀀和表情😀以及带音调的字母a\u0301。"
        val cue = SubtitleCue(5_000, 35_000, text)
        val split = PodcastAsrSupport.splitLongCue(cue)
        assertEquals(3, split.size)
        assertEquals(text, split.joinToString("") { it.text })
        assertEquals(cue.startMs, split.first().startMs)
        assertEquals(cue.endMs, split.last().endMs)
        split.zipWithNext().forEach { (first, second) -> assertEquals(first.endMs, second.startMs) }
        split.forEach {
            assertFalse(Character.isLowSurrogate(it.text.first()))
            assertFalse(Character.isHighSurrogate(it.text.last()))
            assertFalse(it.text.startsWith("\u0301"))
        }
    }

    @Test
    fun `Arabic Tagalog and Vietnamese words keep their original Unicode text`() {
        listOf(
            "هذه ترجمة عربية لاختبار التعرف على الكلام دون فقدان الحروف والحركات",
            "Ito ay pagsubok ng pagkilala sa pagsasalita para sa mga podcast",
            "Đây là bài kiểm tra nhận dạng giọng nói tiếng Việt có dấu",
        ).forEach { text ->
            val split = PodcastAsrSupport.splitLongCue(SubtitleCue(0, 25_000, text))
            assertEquals(3, split.size)
            assertEquals(text, split.joinToString(" ") { it.text })
        }
    }

    @Test
    fun `cueIndexAt finds covering cue and falls back to last started cue`() {
        val cues = listOf(
            SubtitleCue(0, 1_000, "a"),
            SubtitleCue(2_000, 3_000, "b"),
            SubtitleCue(4_000, 5_000, "c"),
        )

        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, 500))
        assertEquals(1, PodcastAsrSupport.cueIndexAt(cues, 2_500))
        // Gap between cue a and b still shows a (last started cue).
        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, 1_500))
        assertEquals(2, PodcastAsrSupport.cueIndexAt(cues, 10_000))
        assertEquals(-1, PodcastAsrSupport.cueIndexAt(emptyList(), 1_000))
    }

    @Test
    fun `cueIndexAt selects new cue at a shared timestamp boundary`() {
        val cues = listOf(
            SubtitleCue(0, 1_000, "a"),
            SubtitleCue(1_000, 2_000, "b"),
            SubtitleCue(2_000, 3_000, "c"),
        )

        assertEquals(0, PodcastAsrSupport.cueIndexAt(cues, 999))
        assertEquals(1, PodcastAsrSupport.cueIndexAt(cues, 1_000))
        assertEquals(2, PodcastAsrSupport.cueIndexAt(cues, 2_000))
        assertEquals(-1, PodcastAsrSupport.cueIndexAt(cues, -1))
    }

    @Test
    fun `cueIndexAt selects latest start for overlapping cues`() {
        val cues = listOf(
            SubtitleCue(0, 4_000, "a"),
            SubtitleCue(1_000, 3_000, "b"),
            SubtitleCue(2_000, 4_000, "c"),
        )

        assertEquals(2, PodcastAsrSupport.cueIndexAt(cues, 2_000))
    }
}

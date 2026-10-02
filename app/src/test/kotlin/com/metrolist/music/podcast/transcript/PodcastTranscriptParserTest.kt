/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastTranscriptParserTest {

    @Test
    fun `parses webvtt with header notes and inline tags`() {
        val vtt = """
            WEBVTT - Podcast transcript

            NOTE This is a note block
            that spans multiple lines.

            intro-cue
            00:00:11.160 --> 00:00:25.160
            <v Speaker>Welcome to <c.highlight>the show</c></v>

            00:00:25.500 --> 00:00:31.000
            Today we talk about
            podcasts and feeds.
        """.trimIndent()

        val cues = PodcastTranscriptParser.parseTranscript(vtt, "text/vtt")

        assertNotNull(cues)
        assertEquals(2, cues!!.size)
        assertEquals(11160L, cues[0].startMs)
        assertEquals(25160L, cues[0].endMs)
        assertEquals("Welcome to the show", cues[0].text)
        assertEquals(25500L, cues[1].startMs)
        assertEquals("Today we talk about podcasts and feeds.", cues[1].text)
    }

    @Test
    fun `parses srt with comma milliseconds`() {
        val srt = """
            1
            00:00:03,400 --> 00:00:07,200
            Hello world

            2
            00:01:02,000 --> 00:01:06,500
            Second line
            continues here
        """.trimIndent()

        val cues = PodcastTranscriptParser.parseTranscript(srt, "application/x-subrip")

        assertNotNull(cues)
        assertEquals(2, cues!!.size)
        assertEquals(3400L, cues[0].startMs)
        assertEquals(7200L, cues[0].endMs)
        assertEquals("Hello world", cues[0].text)
        assertEquals(62000L, cues[1].startMs)
        assertEquals("Second line continues here", cues[1].text)
    }

    @Test
    fun `parses podcast index json with missing end times`() {
        val json = """
            {
              "version": "1.0.0",
              "segments": [
                {"startTime": 11.16, "endTime": 25.16, "body": "First segment"},
                {"startTime": 25.16, "body": "Second segment"}
              ]
            }
        """.trimIndent()

        val cues = PodcastTranscriptParser.parseTranscript(json, "application/json")

        assertNotNull(cues)
        assertEquals(2, cues!!.size)
        assertEquals(11160L, cues[0].startMs)
        assertEquals(25160L, cues[0].endMs)
        assertEquals("First segment", cues[0].text)
        assertEquals(25160L, cues[1].startMs)
        assertEquals(29160L, cues[1].endMs)
    }

    @Test
    fun `plain text transcripts are rejected as unsynced`() {
        assertNull(PodcastTranscriptParser.parseTranscript("Just a plain text transcript.", "text/plain"))
        assertNull(PodcastTranscriptParser.parseTranscript("<html><body>No cues</body></html>", "text/html"))
    }

    @Test
    fun `sniffs vtt content without declared type`() {
        val cues = PodcastTranscriptParser.parseTranscript("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi", null)

        assertNotNull(cues)
        assertEquals("Hi", cues!!.single().text)
    }

    @Test
    fun `extracts transcript refs and channel language from feed`() {
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:podcast="https://podcastindex.org/namespace/1.0" version="2.0">
              <channel>
                <title>Sample Show</title>
                <language>zh-cn</language>
                <podcast:transcript url="https://example.com/feed-transcript.txt" type="text/plain" />
                <item>
                  <guid isPermaLink="false">episode-1</guid>
                  <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="1"/>
                  <podcast:transcript url="https://example.com/ep1.vtt" type="text/vtt" language="zh-cn" rel="captions"/>
                  <podcast:transcript url="https://example.com/ep1.srt" type="application/x-subrip"/>
                  <podcast:transcript url="https://example.com/ep1.txt" type="text/plain"/>
                </item>
                <item>
                  <guid isPermaLink="false">episode-2</guid>
                  <link>https://example.com/ep2</link>
                  <enclosure url="https://cdn.example.com/ep2.mp3" type="audio/mpeg" length="1"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val result = PodcastTranscriptParser.extractFeedTranscripts(feed)

        assertEquals("zh-cn", result.channelLanguage)
        assertEquals(2, result.episodes.size)

        val first = result.episodes[0]
        assertEquals("episode-1", first.guid)
        assertEquals("https://cdn.example.com/ep1.mp3", first.audioUrl)
        assertEquals(3, first.transcripts.size)
        assertEquals("text/vtt", first.transcripts[0].type)
        assertEquals("zh-cn", first.transcripts[0].language)

        // Channel-level transcript must not leak into items.
        assertTrue(result.episodes[1].transcripts.isEmpty())
        assertEquals("https://example.com/ep2", result.episodes[1].link)
    }

    @Test
    fun `best synced ref prefers vtt then srt then json`() {
        val refs = listOf(
            TranscriptRef(url = "https://example.com/a.txt", type = "text/plain"),
            TranscriptRef(url = "https://example.com/a.srt", type = "application/x-subrip"),
            TranscriptRef(url = "https://example.com/a.vtt", type = "text/vtt"),
            TranscriptRef(url = "https://example.com/a.json", type = "application/json"),
        )

        assertEquals("https://example.com/a.vtt", PodcastTranscriptParser.bestSyncedRef(refs)?.url)
        assertEquals(
            "https://example.com/a.srt",
            PodcastTranscriptParser.bestSyncedRef(refs.filterNot { it.type == "text/vtt" })?.url,
        )
        assertNull(PodcastTranscriptParser.bestSyncedRef(refs.take(1)))
    }

    @Test
    fun `best synced ref falls back to syncable url extension`() {
        val refs = listOf(TranscriptRef(url = "https://example.com/a.vtt"))

        assertEquals("https://example.com/a.vtt", PodcastTranscriptParser.bestSyncedRef(refs)?.url)
    }

    @Test
    fun `cue text strips markup and unescapes entities`() {
        val cleaned = PodcastTranscriptParser.cleanCueText("&lt;Intro&gt; A &amp; B<c>\nSecond &nbsp;line</c>")

        assertEquals("<Intro> A & B Second line", cleaned)
    }

    @Test
    fun `parse timestamps tolerate hour-less formats`() {
        val cues = PodcastTranscriptParser.parseTimedCueBlocks(
            "00:04.000 --> 00:07.500\nShort span",
        )

        assertEquals(1, cues.size)
        assertEquals(4000L, cues[0].startMs)
        assertEquals(7500L, cues[0].endMs)
    }
}

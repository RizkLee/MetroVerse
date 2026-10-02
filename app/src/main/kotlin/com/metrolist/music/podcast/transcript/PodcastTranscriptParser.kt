/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.Locale

/** One synchronized subtitle line. Timestamps are milliseconds relative to playback start. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** A `podcast:transcript` reference advertised by a feed item. */
data class TranscriptRef(
    val url: String,
    val type: String? = null,
    val language: String? = null,
)

/** Transcript links found for one `<item>` of a podcast feed. */
data class EpisodeTranscriptRefs(
    val guid: String? = null,
    val link: String? = null,
    val audioUrl: String? = null,
    val transcripts: List<TranscriptRef> = emptyList(),
)

data class PodcastFeedTranscripts(
    val channelLanguage: String? = null,
    val episodes: List<EpisodeTranscriptRefs> = emptyList(),
)

/**
 * Parses Podcasting 2.0 `podcast:transcript` links out of a raw RSS/Atom feed and decodes
 * synchronized transcript files (WebVTT, SubRip and the PodcastIndex JSON format).
 *
 * Pure JVM code so it can be exercised by unit tests without Robolectric.
 */
object PodcastTranscriptParser {

    /** Transcript mime types that carry per-line timings, in selection preference order. */
    val SYNCED_TYPE_PREFERENCE = listOf("text/vtt", "application/x-subrip", "application/json")

    fun extractFeedTranscripts(xml: String): PodcastFeedTranscripts {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())

        var channelLanguage: String? = null
        val episodes = mutableListOf<EpisodeTranscriptRefs>()

        var inItem = false
        var guid: String? = null
        var link: String? = null
        var audioUrl: String? = null
        val transcripts = mutableListOf<TranscriptRef>()
        var textCapture: StringBuilder? = null
        var captureLanguage = false

        fun resetItemState() {
            guid = null
            link = null
            audioUrl = null
            transcripts.clear()
        }

        fun flushItem() {
            if (inItem && (guid != null || link != null || audioUrl != null)) {
                episodes += EpisodeTranscriptRefs(guid, link, audioUrl, transcripts.toList())
            }
            inItem = false
            resetItemState()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = localName(parser.name)
                    when {
                        name.equals("item", ignoreCase = true) && !inItem -> {
                            flushItem()
                            resetItemState()
                            inItem = true
                        }
                        inItem && name.equals("guid", ignoreCase = true) -> {
                            guid = safeNextText(parser)
                        }
                        inItem && name.equals("link", ignoreCase = true) -> {
                            link = safeNextText(parser)
                        }
                        inItem && name.equals("enclosure", ignoreCase = true) -> {
                            val url = parser.getAttributeValue(null, "url")
                                ?.trim()?.takeIf { it.isNotEmpty() }
                            if (url != null) {
                                val type = parser.getAttributeValue(null, "type").orEmpty()
                                val isAudio = type.startsWith("audio", ignoreCase = true) ||
                                    AUDIO_URL_SUFFIXES.any { url.substringBefore('?').lowercase(Locale.US).endsWith(it) }
                                if (audioUrl == null || (isAudio && !type.startsWith("video", true))) {
                                    audioUrl = url
                                }
                            }
                        }
                        name.equals("transcript", ignoreCase = true) && inItem -> {
                            val url = parser.getAttributeValue(null, "url")
                                ?: parser.getAttributeValue(null, "href")
                            if (!url.isNullOrBlank()) {
                                transcripts += TranscriptRef(
                                    url = url.trim(),
                                    type = parser.getAttributeValue(null, "type")
                                        ?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US),
                                    language = parser.getAttributeValue(null, "language")
                                        ?.trim()?.takeIf { it.isNotEmpty() },
                                )
                            }
                        }
                        !inItem && name.equals("language", ignoreCase = true) -> {
                            captureLanguage = true
                            textCapture = StringBuilder()
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    textCapture?.append(parser.text)
                }

                XmlPullParser.END_TAG -> {
                    val name = localName(parser.name)
                    when {
                        name.equals("item", ignoreCase = true) && inItem -> flushItem()
                        name.equals("language", ignoreCase = true) && captureLanguage -> {
                            if (channelLanguage == null) {
                                channelLanguage = textCapture?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                            }
                            captureLanguage = false
                            textCapture = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        flushItem()

        return PodcastFeedTranscripts(channelLanguage, episodes)
    }

    /**
     * Decodes a transcript document into synchronized cues, or returns null when the format
     * carries no timings (plain text / HTML) or cannot be understood.
     */
    fun parseTranscript(content: String, declaredType: String?): List<SubtitleCue>? {
        val type = declaredType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
        return when {
            type == "text/vtt" || content.trimStart().startsWith("WEBVTT", ignoreCase = true) ->
                parseTimedCueBlocks(content)

            type == "application/x-subrip" -> parseTimedCueBlocks(content)

            type == "application/json" -> parsePodcastIndexJson(content)

            else -> sniffAndParse(content)
        }
    }

    /** Picks the best synchronized transcript ref, or null when only unsynced ones exist. */
    fun bestSyncedRef(refs: List<TranscriptRef>): TranscriptRef? =
        SYNCED_TYPE_PREFERENCE.firstNotNullOfOrNull { preferred ->
            refs.firstOrNull { it.type == preferred }
        } ?: refs.firstOrNull { it.type == null && looksSyncedByUrl(it.url) }

    private fun looksSyncedByUrl(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return path.endsWith(".vtt") || path.endsWith(".srt") || path.endsWith(".json")
    }

    private fun sniffAndParse(content: String): List<SubtitleCue>? {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("WEBVTT", ignoreCase = true) -> parseTimedCueBlocks(content)

            trimmed.startsWith("{") -> parsePodcastIndexJson(content)

            TIMING_LINE_REGEX.containsMatchIn(content.take(4096)) -> parseTimedCueBlocks(content)

            else -> null
        }
    }

    private val TIMING_LINE_REGEX =
        Regex("""(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}\s*-->\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}""")

    /**
     * Shared cue-block parser for WebVTT and SubRip. Both formats use the same
     * `id? / timing line / payload lines / blank line` shape.
     */
    fun parseTimedCueBlocks(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lineSequence().toList()
        var index = 0

        fun parseTimestamp(raw: String): Long? {
            val cleaned = raw.trim().replace(',', '.')
            val match = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?""").find(cleaned) ?: return null
            val hours = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
            val minutes = match.groupValues[2].toLong()
            val seconds = match.groupValues[3].toLong()
            val millis = match.groupValues[4].padEnd(3, '0').takeIf { it.isNotEmpty() }?.toLong() ?: 0L
            return ((hours * 3600 + minutes * 60 + seconds) * 1000) + millis
        }

        while (index < lines.size) {
            val line = lines[index].trimEnd()
            if (TIMING_LINE_REGEX.containsMatchIn(line)) {
                val parts = line.split("-->")
                val start = parts.getOrNull(0)
                    ?.trim()?.substringBefore(' ')?.trim()
                    ?.let(::parseTimestamp)
                val end = parts.getOrNull(1)
                    ?.trim()?.substringBefore(' ')?.trim()
                    ?.let(::parseTimestamp)

                if (start != null && end != null) {
                    index++
                    val payload = StringBuilder()
                    while (index < lines.size) {
                        val textLine = lines[index]
                        if (textLine.isBlank()) break
                        if (TIMING_LINE_REGEX.containsMatchIn(textLine.trimEnd())) break
                        if (payload.isNotEmpty()) payload.append('\n')
                        payload.append(textLine.trim())
                        index++
                    }
                    val text = cleanCueText(payload.toString())
                    if (text.isNotEmpty()) {
                        cues += SubtitleCue(
                            startMs = start,
                            endMs = if (end > start) end else start,
                            text = text,
                        )
                    }
                    continue
                }
            }
            index++
        }
        return cues
    }

    /**
     * PodcastIndex JSON transcript format:
     * `{"version":"1.0.0","segments":[{"startTime":11.16,"endTime":25.16,"body":"..."}]}`
     */
    fun parsePodcastIndexJson(content: String): List<SubtitleCue>? {
        val root = runCatching { Json.parseToJsonElement(content) }.getOrNull() as? JsonObject
            ?: return null

        val segments = root["segments"] as? JsonArray ?: return null
        val rawCues = segments.mapNotNull { element ->
            val segment = element as? JsonObject ?: return@mapNotNull null
            val startSeconds = segment.number("startTime") ?: segment.number("start") ?: return@mapNotNull null
            val endSeconds = segment.number("endTime") ?: segment.number("end")
            val body = (segment.string("body") ?: segment.string("text"))?.trim().orEmpty()
            if (body.isEmpty()) return@mapNotNull null
            Triple((startSeconds * 1000).toLong(), endSeconds?.let { (it * 1000).toLong() }, body)
        }

        return rawCues.mapIndexedNotNull { position, (startMs, endMs, body) ->
            val resolvedEnd = endMs
                ?: rawCues.getOrNull(position + 1)?.first?.minus(1)
                ?: startMs + DEFAULT_CUE_DURATION_MS
            val text = cleanCueText(body)
            if (text.isEmpty()) {
                null
            } else {
                SubtitleCue(startMs = startMs, endMs = maxOf(resolvedEnd, startMs), text = text)
            }
        }
    }

    /** Strips WebVTT inline markup and decodes the handful of entities feeds actually use. */
    fun cleanCueText(raw: String): String =
        raw
            .lineSequence()
            .map { line -> line.replace(Regex("<[^>]*>"), "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .unescapeEntities()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.unescapeEntities(): String =
        this
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")

    private fun localName(raw: String): String = raw.substringAfterLast(':')

    private val AUDIO_URL_SUFFIXES =
        listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".m4b")

    private fun safeNextText(parser: XmlPullParser): String? =
        runCatching { parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private const val DEFAULT_CUE_DURATION_MS = 4_000L
}

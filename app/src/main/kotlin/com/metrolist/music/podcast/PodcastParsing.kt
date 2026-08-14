package com.metrolist.music.podcast

import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun normalizeFeedUrl(value: String): String {
    val trimmed = value.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: throw IllegalArgumentException("Invalid podcast feed URL")
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "Podcast feed URL must use http or https"
    }
    require(!uri.host.isNullOrBlank()) { "Podcast feed URL is missing a host" }
    return uri.normalize().toASCIIString()
}

internal fun podcastIdForFeed(feedUrl: String): String =
    "rss_podcast_${feedUrl.sha256().take(32)}"

internal fun episodeIdForFeed(feedUrl: String, guid: String?, mediaUrl: String): String {
    val stableEpisodeKey = guid?.trim()?.takeIf { it.isNotEmpty() } ?: mediaUrl
    return "rss_episode_${"$feedUrl|$stableEpisodeKey".sha256().take(40)}"
}

internal fun parsePodcastDuration(value: String?): Int {
    val duration = value?.trim().orEmpty()
    if (duration.isEmpty()) return -1
    duration.toIntOrNull()?.let { return it.coerceAtLeast(0) }

    val parts = duration.split(':').mapNotNull(String::toIntOrNull)
    if (parts.size == 2) return parts[0] * 60 + parts[1]
    if (parts.size == 3) return parts[0] * 3600 + parts[1] * 60 + parts[2]

    val hours = Regex("(\\d+)\\s*h", RegexOption.IGNORE_CASE).find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val minutes = Regex("(\\d+)\\s*m", RegexOption.IGNORE_CASE).find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val seconds = Regex("(\\d+)\\s*s", RegexOption.IGNORE_CASE).find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val total = hours * 3600 + minutes * 60 + seconds
    return total.takeIf { it > 0 } ?: -1
}

internal fun parsePodcastDate(value: String?): LocalDateTime? {
    val date = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val instant = runCatching { Instant.parse(date) }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        ?: listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss Z",
        ).firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = true }.parse(date)?.toInstant()
            }.getOrNull()
        }
    return instant?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

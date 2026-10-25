/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast

internal fun resolveApplePodcastFeedUrl(appleId: String, catalogFeedUrls: List<String?>): String? {
    val catalogFeedUrl = catalogFeedUrls.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    if (catalogFeedUrl != null) return catalogFeedUrl

    // Apple omits the feed from this Apple News Today catalog entry, but
    // publishes it at this public, continuously updated publisher URL.
    return when (appleId) {
        "1473872585" -> "https://apple.news/podcast/apple_news_today"
        else -> null
    }
}

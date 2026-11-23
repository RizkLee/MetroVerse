/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.artwork

import android.os.Build
import com.metrolist.innertube.models.MUSIC_VIDEO_TYPE_PODCAST_EPISODE
import com.metrolist.music.constants.AnimatedCoverResolution
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.isOpenRssEpisode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val traditionalToSimplifiedTransliterator: Any? by lazy {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.icu.text.Transliterator.getInstance("Traditional-Simplified")
        } else {
            null
        }
    }.getOrNull()
}

internal fun normalizeCjkScript(text: String): String {
    if (text.isBlank()) return text
    val transliterator = traditionalToSimplifiedTransliterator ?: return text
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (transliterator as android.icu.text.Transliterator).transliterate(text)
        } else {
            text
        }
    }.getOrDefault(text)
}

internal fun formatAppleLocaleParam(
    language: String?,
    country: String,
): String? {
    val cleanLanguage = language?.trim()?.lowercase()?.replace('_', '-')?.takeIf(String::isNotBlank) ?: return null
    val cleanCountry = country.trim().lowercase().ifBlank { "us" }
    return when {
        cleanLanguage == "zh-cn" || cleanLanguage == "zh-hans" -> "zh-hans-$cleanCountry"
        cleanLanguage == "zh-tw" || cleanLanguage == "zh-hk" || cleanLanguage == "zh-hant" -> "zh-hant-$cleanCountry"
        cleanLanguage.contains('-') -> cleanLanguage
        else -> "$cleanLanguage-$cleanCountry"
    }
}

internal fun isEligibleForAnimatedCover(metadata: MediaMetadata?): Boolean {
    if (metadata == null) return false
    if (metadata.isEpisode || metadata.isOpenRssEpisode()) return false
    if (metadata.musicVideoType == MUSIC_VIDEO_TYPE_PODCAST_EPISODE) return false
    if (metadata.uploadEntityId != null) return false
    if (metadata.id.startsWith("local_")) return false
    if (metadata.title.isBlank()) return false
    return metadata.artists.any { it.name.isNotBlank() }
}

internal fun isEligibleForAppleMusicArtist(
    artistId: String,
    artistName: String?,
    isPodcastChannel: Boolean,
    isLocalArtist: Boolean,
): Boolean {
    if (isPodcastChannel || isLocalArtist) return false
    if (artistId.isBlank()) return false
    if (artistId.startsWith("LA") || artistId.startsWith("FEmusic_library_privately_owned_artist")) return false
    return !artistName.isNullOrBlank()
}

internal fun isAnimatedArtworkAllowedOnNetwork(
    disableOnMobileData: Boolean,
    isCellular: Boolean,
): Boolean = !disableOnMobileData || !isCellular

internal fun isTrustedArtworkUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (parsed.scheme != "https") return false
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    if (parsed.port != 443) return false
    val host = parsed.host.lowercase()
    return host == "itunes.apple.com" ||
        host.endsWith(".apple.com") ||
        host.endsWith(".mzstatic.com") ||
        host.endsWith(".akamaized.net")
}

private val titleCleanupPatterns =
    listOf(
        Regex(
            """\s*\([^)]*?(official|video|music|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit)[^)]*\)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """\s*\[[^\]]*?(official|video|music|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit)[^\]]*]""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|\s*.*$"""),
        Regex(
            """\s*-\s*(official|video|music|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
    )

private val smartQuotePattern = Regex("""[\u2018\u2019\u201C\u201D]""")
private val whitespacePattern = Regex("""\s+""")
private val streamInfoResolutionRegex = Regex("""RESOLUTION=(\d+)x(\d+)""")
private val streamInfoBandwidthRegex = Regex("""(?:#EXT-X-STREAM-INF:|,)BANDWIDTH=(\d+)""")
private val streamInfoVideoRangeRegex = Regex("""VIDEO-RANGE=([A-Za-z0-9_-]+)""")
private val originRegex = Regex("^(https?://[^/]+)(/.*)?$")
private val duplicateSlashRegex = Regex("(?<!:)//+")

internal fun artworkAlbumSuffix(
    album: String,
    endIndex: Int,
): Pair<Int, String>? {
    var end = endIndex
    while (end > 0 && album[end - 1].isWhitespace()) end--
    if (end == 0) return null
    val closing = album[end - 1]
    var start: Int
    val label: String
    if (closing == ')' || closing == ']') {
        val opening = if (closing == ')') '(' else '['
        var depth = 1
        start = end - 2
        while (start >= 0) {
            when (album[start]) {
                closing -> depth++
                opening -> depth--
            }
            if (depth == 0) break
            start--
        }
        if (start < 0) return null
        var contentStart = start + 1
        while (contentStart < end - 1 && album[contentStart].isWhitespace()) contentStart++
        label =
            if (contentStart < end - 1 && album[contentStart] == '+') {
                "+"
            } else {
                album.substring(contentStart, end - 1).trimEnd().lowercase()
            }
    } else {
        var wordStart = end
        while (wordStart > 0 && album[wordStart - 1].isLetter()) wordStart--
        if (wordStart == end) return null
        label = album.substring(wordStart, end).lowercase()
        start = wordStart
        while (start > 0 && album[start - 1].isWhitespace()) start--
        if (start == 0 || album[start - 1] !in "-–—") return null
        start--
    }
    when (label) {
        "+", "explicit", "clean", "cleaned", "single", "ep" -> Unit
        else -> return null
    }
    while (start > 0 && album[start - 1].isWhitespace()) start--
    return start to label
}

internal fun stripReleaseSuffixes(name: String): String {
    var end = name.length
    while (true) {
        val (start, label) = artworkAlbumSuffix(name, end) ?: break
        if (label != "single" && label != "ep") break
        end = start
    }
    return name.substring(0, end).trimEnd()
}

internal fun stripArtworkAlbumNotes(album: String): String {
    var end = album.length
    val retainedSuffixes = mutableListOf<IntRange>()
    while (true) {
        val (start, label) = artworkAlbumSuffix(album, end) ?: break
        if (label != "+") retainedSuffixes.add(start until end)
        end = start
    }
    return buildString(album.length) {
        append(album, 0, end)
        for (suffix in retainedSuffixes.asReversed()) append(album, suffix.first, suffix.last + 1)
    }.trim()
}

internal fun resolveArtworkExplicitness(
    album: String?,
    trackExplicitness: Boolean?,
): Boolean? {
    if (album.isNullOrBlank()) return trackExplicitness
    val name = stripReleaseSuffixes(stripArtworkAlbumNotes(album))
    if (name.isBlank()) return trackExplicitness
    return when (artworkAlbumSuffix(name, name.length)?.second) {
        "explicit" -> true
        "clean", "cleaned" -> false
        else -> null
    }
}

internal fun stripArtworkEditionSuffix(album: String): String {
    val name = album.trim()
    val withoutRelease = stripReleaseSuffixes(name)
    val suffix = artworkAlbumSuffix(withoutRelease, withoutRelease.length)
    val base =
        when (suffix?.second) {
            "explicit", "clean", "cleaned" -> withoutRelease.substring(0, suffix.first)
            else -> withoutRelease
        }
    return (base + name.substring(withoutRelease.length)).trim()
}

internal fun matchesAppleMusicExplicitness(
    trackExplicitness: String?,
    collectionExplicitness: String?,
    explicit: Boolean,
    albumKnown: Boolean,
): Boolean {
    val value =
        (if (albumKnown) collectionExplicitness ?: trackExplicitness else trackExplicitness ?: collectionExplicitness)
            ?.lowercase()
            ?: return false
    return if (explicit) value == "explicit" else value == "notexplicit" || value == "cleaned"
}

internal fun normalizeTitleText(title: String): String =
    normalizeCjkScript(title)
        .trim()
        .replace(smartQuotePattern, "'")
        .replace(whitespacePattern, " ")
        .lowercase()

internal fun normalizeTrackTitle(title: String): String =
    normalizeTitleText(title).replace('[', '(').replace(']', ')')

internal fun isVariantTitle(
    rawTitle: String,
    cleanTitle: String,
): Boolean = normalizeTitleText(rawTitle) != cleanTitle

internal fun cleanTitleText(title: String): String {
    var cleaned = normalizeTitleText(title)
    for (pattern in titleCleanupPatterns) {
        cleaned = cleaned.replace(pattern, "")
    }
    return cleaned.trim().lowercase()
}

internal fun normalizeArtistProfileName(artistName: String): String =
    normalizeCjkScript(artistName)
        .trim()
        .lowercase()
        .replace(smartQuotePattern, "'")
        .replace(whitespacePattern, " ")

internal fun normalizeArtist(artistName: String): String =
    normalizeCjkScript(artistName)
        .trim()
        .lowercase()
        .replace(" & ", ", ")
        .replace(" and ", ", ")
        .replace(" feat. ", ", ")
        .replace(" ft. ", ", ")
        .replace(" featuring ", ", ")
        .replace(" with ", ", ")
        .replace(" x ", ", ")
        .replace(" X ", ", ")
        .split(",")
        .firstOrNull()
        ?.trim()
        ?: normalizeCjkScript(artistName).trim().lowercase()

internal fun normalizeAlbum(name: String): String =
    normalizeTitleText(name)
        .let(::stripArtworkAlbumNotes)
        .replace('[', '(')
        .replace(']', ')')

internal fun normalizeAlbumOrNull(album: String?): String? =
    album?.let { stripArtworkEditionSuffix(normalizeAlbum(it)) }?.takeIf { stripReleaseSuffixes(it).isNotBlank() }

internal fun albumNamesMatch(
    itunesAlbum: String,
    targetAlbum: String,
): Boolean {
    val a = stripArtworkEditionSuffix(normalizeAlbum(itunesAlbum))
    val b = stripArtworkEditionSuffix(normalizeAlbum(targetAlbum))
    return stripReleaseSuffixes(a) == stripReleaseSuffixes(b)
}

internal fun isSelfTitledRelease(
    collectionName: String?,
    cleanTitle: String,
): Boolean {
    if (collectionName == null) return false
    val base = stripReleaseSuffixes(stripArtworkEditionSuffix(normalizeAlbum(collectionName)))
    return normalizeTrackTitle(base) == normalizeTrackTitle(cleanTitle)
}

internal fun escapeCacheComponent(component: String): String =
    component.replace("%", "%25").replace("|", "%7c")

internal fun resolvePlaylistUrl(
    masterUrl: String,
    variant: String,
): String? {
    if (variant.startsWith("https://") || variant.startsWith("http://")) return variant
    val match = originRegex.find(masterUrl) ?: return null
    val origin = match.groupValues[1]
    if (variant.startsWith("/")) return origin + variant
    val path = match.groupValues.getOrNull(2).orEmpty().substringBeforeLast('/', "")
    return "$origin/$path/$variant".replace(duplicateSlashRegex, "/")
}

internal fun selectBestHlsVariant(
    masterUrl: String,
    playlistBody: String,
    resolution: AnimatedCoverResolution,
    urlTrustCheck: (String) -> Boolean = ::isTrustedArtworkUrl,
): String? {
    val lines = playlistBody.lines()
    var currentInfo: String? = null
    var bestSdrVariant: String? = null
    var bestSdrPixels = 0L
    var bestSdrBandwidth = 0L
    var bestAnyVariant: String? = null
    var bestAnyPixels = 0L
    var bestAnyBandwidth = 0L
    var fallbackVariant: String? = null
    var fallbackPixels = Long.MAX_VALUE
    val maxDimension = resolution.maxDimension

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
            currentInfo = trimmed
        } else if (!trimmed.startsWith("#") && trimmed.isNotBlank() && currentInfo != null) {
            val variantUrl = resolvePlaylistUrl(masterUrl, trimmed)
            if (variantUrl == null || !urlTrustCheck(variantUrl) || !variantUrl.contains(".m3u8")) {
                currentInfo = null
                continue
            }
            val resMatch = streamInfoResolutionRegex.find(currentInfo)
            if (resMatch != null) {
                val width = resMatch.groupValues[1].toInt()
                val height = resMatch.groupValues[2].toInt()
                val pixels = width.toLong() * height.toLong()
                if (pixels < fallbackPixels) {
                    fallbackPixels = pixels
                    fallbackVariant = variantUrl
                }
                val bandwidth =
                    streamInfoBandwidthRegex
                        .find(currentInfo)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                        ?: 0L
                val videoRange =
                    streamInfoVideoRangeRegex
                        .find(currentInfo)
                        ?.groupValues
                        ?.getOrNull(1)
                val isSdrCompatible = videoRange == null || videoRange.equals("SDR", ignoreCase = true)

                if (minOf(width, height) <= maxDimension) {
                    if (isSdrCompatible &&
                        (pixels > bestSdrPixels || (pixels == bestSdrPixels && bandwidth > bestSdrBandwidth))
                    ) {
                        bestSdrVariant = variantUrl
                        bestSdrPixels = pixels
                        bestSdrBandwidth = bandwidth
                    }
                    if (pixels > bestAnyPixels || (pixels == bestAnyPixels && bandwidth > bestAnyBandwidth)) {
                        bestAnyVariant = variantUrl
                        bestAnyPixels = pixels
                        bestAnyBandwidth = bandwidth
                    }
                }
            }
            currentInfo = null
        }
    }

    return bestSdrVariant ?: bestAnyVariant ?: fallbackVariant
}

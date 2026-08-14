package com.metrolist.music.podcast

import kotlinx.serialization.Serializable

/** A lightweight Apple Podcasts result. RSS data is fetched only when the item is opened. */
data class PodcastDiscoverItem(
    val appleId: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val description: String? = null,
    val feedUrl: String? = null,
)

@Serializable
internal data class ApplePodcastSearchResponse(
    val results: List<ApplePodcastSearchResult> = emptyList(),
)

@Serializable
internal data class ApplePodcastSearchResult(
    val collectionId: Long = 0,
    val collectionName: String = "",
    val trackName: String = "",
    val artistName: String = "",
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
    val feedUrl: String? = null,
) {
    fun toDiscoverItem() = PodcastDiscoverItem(
        appleId = collectionId.toString(),
        title = collectionName.ifBlank { trackName },
        author = artistName,
        artworkUrl = artworkUrl600 ?: artworkUrl100,
        feedUrl = feedUrl,
    )
}

@Serializable
internal data class ApplePodcastLookupResponse(
    val results: List<ApplePodcastLookupResult> = emptyList(),
)

@Serializable
internal data class ApplePodcastLookupResult(
    val feedUrl: String? = null,
)

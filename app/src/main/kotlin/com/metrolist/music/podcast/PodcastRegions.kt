package com.metrolist.music.podcast

import java.util.Locale

data class PodcastRegion(
    val code: String,
)

val supportedPodcastRegions = listOf(
    PodcastRegion("US"),
    PodcastRegion("GB"),
    PodcastRegion("CA"),
    PodcastRegion("AU"),
    PodcastRegion("CN"),
    PodcastRegion("HK"),
    PodcastRegion("TW"),
    PodcastRegion("SG"),
    PodcastRegion("JP"),
    PodcastRegion("KR"),
    PodcastRegion("IN"),
    PodcastRegion("DE"),
    PodcastRegion("FR"),
    PodcastRegion("BR"),
    PodcastRegion("MX"),
)

fun defaultPodcastRegionCode(): String {
    val deviceCountry = Locale.getDefault().country.uppercase(Locale.US)
    return deviceCountry.takeIf { code -> supportedPodcastRegions.any { it.code == code } } ?: "US"
}

fun normalizePodcastRegionCode(code: String): String =
    code.uppercase(Locale.US).takeIf { value -> supportedPodcastRegions.any { it.code == value } } ?: "US"

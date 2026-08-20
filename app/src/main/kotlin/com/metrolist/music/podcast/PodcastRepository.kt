package com.metrolist.music.podcast

import android.content.Context
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.models.MediaMetadata
import com.prof18.rssparser.RssParser
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

const val PODCAST_USER_AGENT = "MetroVerse/0.5.4 (Android; Podcast)"

@Singleton
class PodcastRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun search(
        query: String,
        country: String = defaultCountry(),
    ): List<PodcastDiscoverItem> {
        if (query.isBlank()) return emptyList()
        val response = client.get("https://itunes.apple.com/search") {
            parameter("media", "podcast")
            parameter("entity", "podcast")
            parameter("limit", 40)
            parameter("country", country)
            parameter("term", query.trim())
            header(HttpHeaders.UserAgent, PODCAST_USER_AGENT)
        }
        check(response.status.isSuccess()) { "Apple Podcasts search failed (${response.status.value})" }
        return json.decodeFromString<ApplePodcastSearchResponse>(response.bodyAsText())
            .results
            .map(ApplePodcastSearchResult::toDiscoverItem)
            .filter { it.appleId.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.appleId }
    }

    suspend fun topPodcasts(
        country: String = defaultCountry(),
        limit: Int = 25,
        genreId: Int? = null,
    ): List<PodcastDiscoverItem> {
        val genreSegment = genreId?.let { "genre=$it/" }.orEmpty()
        val response = client.get(
            "https://itunes.apple.com/${country.lowercase(Locale.US)}/rss/toppodcasts/limit=$limit/${genreSegment}explicit=true/json",
        ) {
            header(HttpHeaders.UserAgent, PODCAST_USER_AGENT)
        }
        check(response.status.isSuccess()) { "Apple Podcasts chart failed (${response.status.value})" }

        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val entries = root["feed"]?.jsonObject?.get("entry")?.jsonArray.orEmpty()
        return entries.mapNotNull { element ->
            val entry = element.jsonObject
            val appleId = entry["id"]?.jsonObject
                ?.get("attributes")?.jsonObject
                ?.get("im:id")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val title = entry["im:name"]?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull
                ?: entry["title"]?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val author = entry["im:artist"]?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull.orEmpty()
            val artwork = entry["im:image"]?.jsonArray?.lastOrNull()?.jsonObject
                ?.get("label")?.jsonPrimitive?.contentOrNull
            val description = entry["summary"]?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull
            PodcastDiscoverItem(
                appleId = appleId,
                title = title,
                author = author,
                artworkUrl = artwork,
                description = description,
            )
        }
    }

    suspend fun importPodcast(
        item: PodcastDiscoverItem,
        subscribe: Boolean = false,
    ): PodcastEntity {
        val feedUrl = item.feedUrl ?: resolveAppleFeedUrl(item.appleId)
        return fetchFeed(feedUrl, subscribe)
    }

    suspend fun fetchFeed(
        inputUrl: String,
        subscribe: Boolean? = null,
    ): PodcastEntity {
        val feedUrl = normalizeFeedUrl(inputUrl)
        val response = client.get(feedUrl) {
            header(HttpHeaders.Accept, "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            header(HttpHeaders.UserAgent, PODCAST_USER_AGENT)
        }
        check(response.status.isSuccess()) { "Podcast feed failed (${response.status.value})" }

        val rss = RssParser().parse(response.bodyAsText())
        val existing = database.podcastByFeedUrl(feedUrl)
        val podcastId = existing?.id ?: podcastIdForFeed(feedUrl)
        val title = rss.title?.trim().orEmpty().ifBlank { existing?.title ?: feedUrl }
        val author = rss.itunesChannelData?.author?.trim().orEmpty().ifBlank { existing?.author.orEmpty() }
        val artwork = rss.image?.url?.trim().orEmpty().ifBlank { existing?.thumbnailUrl.orEmpty() }
        val now = LocalDateTime.now()
        val podcast = PodcastEntity(
            id = podcastId,
            title = title,
            author = author.ifBlank { null },
            thumbnailUrl = artwork.ifBlank { null },
            feedUrl = feedUrl,
            description = cleanText(rss.description).ifBlank { existing?.description },
            websiteUrl = rss.link?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.websiteUrl,
            language = existing?.language,
            bookmarkedAt = when (subscribe) {
                true -> existing?.bookmarkedAt ?: now
                false, null -> existing?.bookmarkedAt
            },
            lastUpdateTime = now,
        )
        database.upsert(podcast)

        rss.items.asSequence()
            .mapNotNull { item ->
                val mediaUrl = (item.audio ?: item.video)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val episodeId = episodeIdForFeed(feedUrl, item.guid, mediaUrl)
                MediaMetadata(
                    id = episodeId,
                    title = item.title?.trim().orEmpty().ifBlank { context.getString(android.R.string.untitled) },
                    artists = listOf(
                        MediaMetadata.Artist(
                            id = podcastId,
                            name = item.author?.trim().orEmpty().ifBlank { author.ifBlank { title } },
                        ),
                    ),
                    duration = parsePodcastDuration(item.itunesItemData?.duration),
                    thumbnailUrl = (item.itunesItemData?.image ?: item.image)?.trim().orEmpty()
                        .ifBlank { artwork }
                        .ifBlank { null },
                    album = MediaMetadata.Album(id = podcastId, title = title),
                    isEpisode = true,
                    mediaUrl = mediaUrl,
                    shareUrl = item.link?.trim()?.takeIf { it.isNotEmpty() },
                    description = cleanText(item.description).ifBlank { null },
                    publishedAt = parsePodcastDate(item.pubDate),
                )
            }
            .take(MAX_EPISODES_PER_FEED)
            .forEach { metadata ->
                val existingSong = database.getSongByIdBlocking(metadata.id)
                if (existingSong == null) {
                    database.insert(metadata)
                } else {
                    database.update(existingSong, metadata)
                }
            }

        return podcast
    }

    suspend fun setSubscribed(podcastId: String, subscribed: Boolean) {
        val podcast = database.podcastOnce(podcastId) ?: return
        database.update(
            podcast.copy(
                bookmarkedAt = if (subscribed) podcast.bookmarkedAt ?: LocalDateTime.now() else null,
                lastUpdateTime = LocalDateTime.now(),
            ),
        )
    }

    suspend fun refreshSubscribed(): List<Result<PodcastEntity>> =
        database.subscribedRssPodcastsOnce().map { podcast ->
            runCatching { fetchFeed(requireNotNull(podcast.feedUrl), subscribe = null) }
        }

    private suspend fun resolveAppleFeedUrl(appleId: String): String {
        val response = client.get("https://itunes.apple.com/lookup") {
            parameter("id", appleId)
            parameter("entity", "podcast")
            header(HttpHeaders.UserAgent, PODCAST_USER_AGENT)
        }
        check(response.status.isSuccess()) { "Apple Podcasts lookup failed (${response.status.value})" }
        return json.decodeFromString<ApplePodcastLookupResponse>(response.bodyAsText())
            .results
            .firstNotNullOfOrNull { it.feedUrl }
            ?: error("This podcast does not expose an RSS feed")
    }

    private fun defaultCountry(): String =
        Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "US"

    private fun cleanText(value: String?): String =
        value?.let { Jsoup.parse(it).text().trim() }.orEmpty()

    private companion object {
        const val MAX_EPISODES_PER_FEED = 1000
    }
}

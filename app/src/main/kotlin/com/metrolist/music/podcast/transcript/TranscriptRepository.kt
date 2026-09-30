/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import com.metrolist.music.podcast.PODCAST_USER_AGENT
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where the cues of a loaded transcript came from. */
enum class TranscriptSource {
    /** Publisher-provided `podcast:transcript` file. */
    FEED,

    /** On-device speech recognition, generated on demand. */
    ON_DEVICE,
}

/** A fully loaded transcript for one episode. */
data class EpisodeTranscript(
    val episodeId: String,
    val source: TranscriptSource,
    val cues: List<SubtitleCue>,
    val language: String? = null,
)

@Serializable
private data class StoredTranscriptRef(
    val url: String,
    val type: String? = null,
    val language: String? = null,
)

@Serializable
private data class TranscriptIndexFeed(
    var language: String? = null,
    var episodes: MutableMap<String, List<StoredTranscriptRef>> = mutableMapOf(),
)

@Serializable
private data class TranscriptIndex(
    val feeds: MutableMap<String, TranscriptIndexFeed> = mutableMapOf(),
)

@Serializable
private data class StoredCue(
    val s: Long,
    val e: Long,
    val t: String,
)

@Serializable
private data class StoredTranscript(
    val source: TranscriptSource = TranscriptSource.FEED,
    val language: String? = null,
    val cues: List<StoredCue>,
)

/**
 * File-backed store for podcast transcripts.
 *
 * Room stays untouched: the feed-to-episode transcript mapping and the decoded cue lists live
 * in private files, keyed by the deterministic episode ids produced while parsing the feed.
 */
@Singleton
class TranscriptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    private val indexMutex = Mutex()
    private var cachedIndex: TranscriptIndex? = null

    private val indexFile: File
        get() = File(File(context.filesDir, "podcast"), "transcript_index.json")

    private val transcriptsDir: File
        get() = File(context.filesDir, "podcast").resolve("transcripts")

    /** Records transcript links discovered while parsing a feed (replaces previous entries). */
    suspend fun updateFeedTranscripts(
        feedUrl: String,
        channelLanguage: String?,
        episodes: List<EpisodeTranscriptRefs>,
        episodeIdOf: (EpisodeTranscriptRefs) -> String?,
    ) = indexMutex.withLock {
        val index = loadIndex()
        val feed = index.feeds.getOrPut(feedUrl) { TranscriptIndexFeed() }
        feed.language = channelLanguage ?: feed.language
        val mutable = feed.episodes.toMutableMap()
        episodes.forEach { refs ->
            val episodeId = episodeIdOf(refs) ?: return@forEach
            val stored = refs.transcripts.map {
                StoredTranscriptRef(url = it.url, type = it.type, language = it.language)
            }
            if (stored.isEmpty()) {
                mutable.remove(episodeId)
            } else {
                mutable[episodeId] = stored
            }
        }
        feed.episodes = mutable
        persistIndex(index)
    }

    suspend fun feedLanguage(feedUrl: String): String? = indexMutex.withLock {
        loadIndex().feeds[feedUrl]?.language
    }

    /** Returns the stored transcript refs for an episode, or null when the feed has none. */
    suspend fun transcriptRefs(episodeId: String): List<TranscriptRef>? = indexMutex.withLock {
        loadIndex()
            .feeds
            .values
            .asSequence()
            .mapNotNull { it.episodes[episodeId] }
            .firstOrNull()
            ?.map { TranscriptRef(it.url, it.type, it.language) }
    }

    /** Loads a transcript: cached decoded cues first, otherwise downloads and parses. */
    suspend fun loadTranscript(episodeId: String): EpisodeTranscript? {
        loadCachedTranscript(episodeId)?.let { return it }

        val refs = transcriptRefs(episodeId) ?: return null
        val ref = PodcastTranscriptParser.bestSyncedRef(refs) ?: return null
        val content = download(ref.url) ?: return null
        val cues = PodcastTranscriptParser.parseTranscript(content, ref.type) ?: return null
        if (cues.isEmpty()) return null

        val transcript = EpisodeTranscript(
            episodeId = episodeId,
            source = TranscriptSource.FEED,
            cues = cues,
            language = ref.language,
        )
        storeTranscript(episodeId, transcript)
        return transcript
    }

    /** Persists cues produced outside the feed (on-device speech recognition). */
    suspend fun storeOnDeviceTranscript(
        episodeId: String,
        cues: List<SubtitleCue>,
        language: String? = null,
    ) {
        storeTranscript(
            episodeId,
            EpisodeTranscript(
                episodeId = episodeId,
                source = TranscriptSource.ON_DEVICE,
                cues = cues,
                language = language,
            ),
        )
    }

    suspend fun cachedTranscript(episodeId: String): EpisodeTranscript? =
        loadCachedTranscript(episodeId)

    /** Removes every transcript artifact of one episode (index entry, decoded cues, translations). */
    suspend fun clearEpisode(episodeId: String) = indexMutex.withLock {
        val index = loadIndex()
        index.feeds.values.forEach { feed -> feed.episodes.remove(episodeId) }
        persistIndex(index)
        withContext(Dispatchers.IO) {
            transcriptFile(episodeId).delete()
            File(File(context.filesDir, "podcast"), "translations")
                .listFiles()
                ?.filter { it.name.startsWith("${episodeId.takeLast(48)}_") }
                ?.forEach { it.delete() }
        }
    }

    suspend fun storeTranslation(
        episodeId: String,
        targetLanguage: String,
        provider: String,
        translations: Map<Int, String>,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "podcast/translations").apply { mkdirs() }
            val existing = loadTranslation(episodeId, targetLanguage, provider).toMutableMap()
            existing.putAll(translations)
            dir.resolve(translationFileName(episodeId, targetLanguage, provider))
                .writeText(json.encodeToString(existing))
        }.isSuccess
    }

    suspend fun loadTranslation(
        episodeId: String,
        targetLanguage: String,
        provider: String,
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(context.filesDir, "podcast/translations")
                .resolve(translationFileName(episodeId, targetLanguage, provider))
            if (!file.exists()) return@runCatching emptyMap()
            json.decodeFromString<Map<String, String>>(file.readText())
                .mapKeys { entry -> entry.key.toInt() }
        }.getOrDefault(emptyMap())
    }

    private suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, PODCAST_USER_AGENT)
                header(HttpHeaders.Accept, "text/vtt, application/x-subrip, application/json, text/*, */*")
            }
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.getOrNull()
    }

    private suspend fun storeTranscript(episodeId: String, transcript: EpisodeTranscript) =
        withContext(Dispatchers.IO) {
            runCatching {
                transcriptsDir.mkdirs()
                transcriptFile(episodeId).writeText(
                    json.encodeToString(
                        StoredTranscript(
                            source = transcript.source,
                            language = transcript.language,
                            cues = transcript.cues.map { StoredCue(it.startMs, it.endMs, it.text) },
                        ),
                    ),
                )
            }
        }

    private suspend fun loadCachedTranscript(episodeId: String): EpisodeTranscript? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = transcriptFile(episodeId)
                if (!file.exists()) return@runCatching null
                val stored = json.decodeFromString<StoredTranscript>(file.readText())
                EpisodeTranscript(
                    episodeId = episodeId,
                    source = stored.source,
                    language = stored.language,
                    cues = stored.cues.map { SubtitleCue(it.s, it.e, it.t) },
                )
            }.getOrNull()
        }

    private fun transcriptFile(episodeId: String): File =
        transcriptsDir.resolve("${episodeId.takeLast(48)}.json")

    private fun translationFileName(episodeId: String, targetLanguage: String, provider: String): String =
        "${episodeId.takeLast(48)}_${provider}_${targetLanguage.replace('/', '_')}.json"

    private suspend fun loadIndex(): TranscriptIndex {
        cachedIndex?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (!indexFile.exists()) return@runCatching null
                json.decodeFromString<TranscriptIndex>(indexFile.readText())
            }.getOrNull()
        } ?: TranscriptIndex()
        cachedIndex = loaded
        return loaded
    }

    private suspend fun persistIndex(index: TranscriptIndex) = withContext(Dispatchers.IO) {
        runCatching {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(json.encodeToString(index))
        }
        cachedIndex = index
    }
}

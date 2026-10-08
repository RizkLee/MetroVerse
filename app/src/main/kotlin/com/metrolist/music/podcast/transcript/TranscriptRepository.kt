/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import com.metrolist.music.cache.TextCacheSignals
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
import kotlinx.coroutines.CancellationException
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

/** Complete publisher/ASR subtitles, or an explicitly unfinished ASR checkpoint. */
data class EpisodeTranscript(
    val episodeId: String,
    val source: TranscriptSource,
    val cues: List<SubtitleCue>,
    val language: String? = null,
    val isComplete: Boolean = true,
    val progressPercent: Int = if (isComplete) 100 else 0,
    val resumePositionMs: Long = 0,
    val mediaSourceFingerprint: String? = null,
    val recognizerFingerprint: String? = null,
)

data class PodcastTextCacheEntry(
    val key: String,
    val bytes: Long,
    val lastAccessMs: Long,
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
    val isComplete: Boolean = true,
    val progressPercent: Int = if (isComplete) 100 else 0,
    val resumePositionMs: Long = 0,
    val mediaSourceFingerprint: String? = null,
    val recognizerFingerprint: String? = null,
)

@Serializable
private data class StoredTranslations(
    val version: Int = 2,
    val configuration: String,
    val entries: Map<String, String>,
)

private data class PendingSubtitleSave<T>(val value: T, val lastAccessMs: Long)

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

    private val fileMutex = Mutex()
    private val pendingTranscriptSaves = linkedMapOf<String, PendingSubtitleSave<EpisodeTranscript>>()
    private val pendingTranslationSaves = linkedMapOf<Pair<String, String>, PendingSubtitleSave<Map<String, String>>>()
    private var cachedIndex: TranscriptIndex? = null

    private val indexFile: File
        get() = File(File(context.filesDir, "podcast"), "transcript_index.json")

    private val transcriptsDir: File
        get() = File(context.filesDir, "podcast").resolve("transcripts")

    private val translationsDir: File
        get() = File(context.filesDir, "podcast").resolve("translations")

    /** Records transcript links discovered while parsing a feed (replaces previous entries). */
    suspend fun updateFeedTranscripts(
        feedUrl: String,
        channelLanguage: String?,
        episodes: List<EpisodeTranscriptRefs>,
        episodeIdOf: (EpisodeTranscriptRefs) -> String?,
    ) = fileMutex.withLock {
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

    suspend fun feedLanguage(feedUrl: String): String? = fileMutex.withLock {
        loadIndex().feeds[feedUrl]?.language
    }

    /** Returns the stored transcript refs for an episode, or null when the feed has none. */
    suspend fun transcriptRefs(episodeId: String): List<TranscriptRef>? = fileMutex.withLock {
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
        loadCachedTranscript(episodeId)?.takeIf { it.source == TranscriptSource.FEED }?.let { return it }

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
    ): Result<Unit> = storeTranscript(
            episodeId,
            EpisodeTranscript(
                episodeId = episodeId,
                source = TranscriptSource.ON_DEVICE,
                cues = cues,
                language = language,
            ),
        )

    suspend fun storeOnDeviceTranscript(transcript: EpisodeTranscript): Result<Unit> {
        require(transcript.source == TranscriptSource.ON_DEVICE)
        return storeTranscript(transcript.episodeId, transcript)
    }

    suspend fun cachedTranscript(episodeId: String): EpisodeTranscript? =
        loadCachedTranscript(episodeId)

    internal suspend fun pendingTranscript(episodeId: String): EpisodeTranscript? = fileMutex.withLock {
        pendingTranscriptSaves[episodeId]?.value
    }

    /** Removes every transcript artifact of one episode (index entry, decoded cues, translations). */
    suspend fun clearEpisode(episodeId: String) = fileMutex.withLock {
        val index = loadIndex()
        index.feeds.values.forEach { feed -> feed.episodes.remove(episodeId) }
        persistIndex(index)
        withContext(Dispatchers.IO) {
            cachedFilesForKey(episodeId.takeLast(48)).forEach { it.delete() }
            removePendingText(episodeId.takeLast(48))
            TextCacheSignals.notifyChanged()
        }
    }

    suspend fun cachedTextEntries(): List<PodcastTextCacheEntry> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val transcripts = transcriptsDir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
            val knownKeys = transcripts.map { it.nameWithoutExtension }.toHashSet()
            val filesByKey = transcripts.groupBy { it.nameWithoutExtension }.mapValues { it.value.toMutableList() }.toMutableMap()
            translationsDir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.forEach { file ->
                val key = file.name.takeIf { it.length > 49 && it[48] == '_' }?.take(48)
                    ?: file.name.indices.asSequence().take(48)
                        .filter { file.name[it] == '_' }
                        .map { file.name.take(it) }
                        .lastOrNull { it in knownKeys }
                if (key != null && isSafeCacheKey(key)) filesByKey.getOrPut(key) { mutableListOf() }.add(file)
            }
            val pendingAccess = mutableMapOf<String, Long>()
            pendingTranscriptSaves.forEach { (episodeId, pending) -> pendingAccess[episodeId.takeLast(48)] = pending.lastAccessMs }
            pendingTranslationSaves.forEach { (identity, pending) ->
                val key = identity.first.takeLast(48)
                pendingAccess[key] = maxOf(pendingAccess[key] ?: 0, pending.lastAccessMs)
            }
            pendingAccess.keys.forEach { filesByKey.getOrPut(it) { mutableListOf() } }
            filesByKey.mapNotNull { (key, files) ->
                key.takeIf(::isSafeCacheKey)?.let {
                    PodcastTextCacheEntry(
                        key, files.sumOf { it.length() },
                        maxOf(files.maxOfOrNull { it.lastModified() } ?: 0, pendingAccess[key] ?: 0),
                    )
                }
            }
        }
    }

    /** Cache eviction never changes discovery metadata or an episode currently displayed. */
    suspend fun removeCachedText(key: String): Boolean = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (!isSafeCacheKey(key) || TextCacheSignals.isProtected(key)) return@withLock false
            val results = cachedFilesForKey(key).map { !it.exists() || it.delete() }
            val removed = results.all { it }
            val removedPending = removed && removePendingText(key)
            if (results.isNotEmpty() || removedPending) TextCacheSignals.notifyChanged()
            removed
        }
    }

    suspend fun storeTranslation(
        episodeId: String,
        configurationKey: String,
        translations: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val identity = episodeId to configurationKey
            val combined = mutableMapOf<String, String>()
            try {
                require(isFingerprint(configurationKey) && translations.keys.all(::isFingerprint))
                val file = translationsDir.resolve(translationFileName(episodeId, configurationKey))
                combined.putAll(readTranslation(file, configurationKey))
                combined.putAll(pendingTranslationSaves[identity]?.value.orEmpty())
                combined.putAll(translations)
                writeSubtitleCacheAtomically(file, json.encodeToString(StoredTranslations(configuration = configurationKey, entries = combined)))
                pendingTranslationSaves.remove(identity)
                TextCacheSignals.notifyChanged()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (combined.isNotEmpty()) retainPending(pendingTranslationSaves, identity, combined.toMap())
                false
            }
        }
    }

    suspend fun loadTranslation(
        episodeId: String,
        configurationKey: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (!isFingerprint(configurationKey)) return@withLock emptyMap()
            readTranslation(translationsDir.resolve(translationFileName(episodeId, configurationKey)), configurationKey) +
                pendingTranslationSaves[episodeId to configurationKey]?.value.orEmpty()
        }
    }

    internal suspend fun hasPendingTranslationSave(episodeId: String, configurationKey: String): Boolean = fileMutex.withLock {
        episodeId to configurationKey in pendingTranslationSaves
    }

    private fun readTranslation(file: File, configurationKey: String): Map<String, String> =
        runCatching {
            if (!file.exists()) return@runCatching emptyMap()
            val stored = json.decodeFromString<StoredTranslations>(file.readText())
            if (stored.version != 2 || stored.configuration != configurationKey) return@runCatching emptyMap()
            stored.entries.filter { isFingerprint(it.key) && it.value.isNotBlank() }.also { touch(file) }
        }.getOrDefault(emptyMap())

    private suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, PODCAST_USER_AGENT)
                header(HttpHeaders.Accept, "text/vtt, application/x-subrip, application/json, text/*, */*")
            }
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.getOrNull()
    }

    private suspend fun storeTranscript(episodeId: String, transcript: EpisodeTranscript): Result<Unit> =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                try {
                    require(transcript.resumePositionMs >= 0)
                    require(transcript.progressPercent in 0..(if (transcript.isComplete) 100 else 99))
                    writeSubtitleCacheAtomically(
                        transcriptFile(episodeId),
                        json.encodeToString(
                            StoredTranscript(
                                source = transcript.source,
                                language = transcript.language,
                                cues = transcript.cues.map { StoredCue(it.startMs, it.endMs, it.text) },
                                isComplete = transcript.isComplete,
                                progressPercent = transcript.progressPercent,
                                resumePositionMs = transcript.resumePositionMs,
                                mediaSourceFingerprint = transcript.mediaSourceFingerprint,
                                recognizerFingerprint = transcript.recognizerFingerprint,
                            ),
                        ),
                    )
                    pendingTranscriptSaves.remove(episodeId)
                    TextCacheSignals.notifyChanged()
                    Result.success(Unit)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (transcript.source == TranscriptSource.ON_DEVICE && transcript.cues.isNotEmpty()) {
                        retainPending(pendingTranscriptSaves, episodeId, transcript)
                    }
                    Result.failure(error)
                }
            }
        }

    private suspend fun loadCachedTranscript(episodeId: String): EpisodeTranscript? =
        withContext(Dispatchers.IO) {
            fileMutex.withLock { readCachedTranscript(episodeId) }
        }

    private fun readCachedTranscript(episodeId: String): EpisodeTranscript? = runCatching {
        val file = transcriptFile(episodeId)
        if (!file.exists()) return@runCatching null
        val stored = json.decodeFromString<StoredTranscript>(file.readText())
        if (stored.resumePositionMs < 0 || stored.progressPercent !in 0..100) return@runCatching null
        EpisodeTranscript(
            episodeId = episodeId,
            source = stored.source,
            language = stored.language,
            cues = stored.cues.map { SubtitleCue(it.s, it.e, it.t) },
            isComplete = stored.isComplete,
            progressPercent = if (stored.isComplete) 100 else stored.progressPercent.coerceAtMost(99),
            resumePositionMs = stored.resumePositionMs,
            mediaSourceFingerprint = stored.mediaSourceFingerprint,
            recognizerFingerprint = stored.recognizerFingerprint,
        ).also { touch(file) }
    }.getOrNull()

    private fun cachedFilesForKey(key: String): List<File> = buildList {
        transcriptsDir.resolve("$key.json").takeIf { it.isFile }?.let(::add)
        translationsDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.name.startsWith("${key}_") }
            .let(::addAll)
    }

    private fun removePendingText(key: String): Boolean {
        val removedTranscript = pendingTranscriptSaves.keys.removeAll { it.takeLast(48) == key }
        val removedTranslations = pendingTranslationSaves.keys.removeAll { it.first.takeLast(48) == key }
        return removedTranscript || removedTranslations
    }

    private fun <K, V> retainPending(saves: MutableMap<K, PendingSubtitleSave<V>>, key: K, value: V) {
        saves.remove(key)
        saves[key] = PendingSubtitleSave(value, System.currentTimeMillis())
        while (saves.size > 4) saves.remove(saves.keys.first())
        TextCacheSignals.notifyChanged()
    }

    private fun isSafeCacheKey(key: String): Boolean =
        key.isNotEmpty() && key.length <= 48 && key.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun touch(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() >= 60_000) file.setLastModified(now)
    }

    private fun transcriptFile(episodeId: String): File =
        transcriptsDir.resolve("${episodeId.takeLast(48)}.json")

    private fun translationFileName(episodeId: String, configurationKey: String): String =
        "${episodeId.takeLast(48)}_v2_$configurationKey.json"

    private fun isFingerprint(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

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

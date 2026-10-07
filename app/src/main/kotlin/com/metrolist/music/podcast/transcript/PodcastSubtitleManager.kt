/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.cache.TextCacheSignals
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.podcast.PodcastRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

internal fun isCurrentSubtitleRequest(
    requestGeneration: Long,
    currentGeneration: Long,
    requestEpisodeId: String,
    currentEpisodeId: String?,
): Boolean = requestGeneration == currentGeneration && requestEpisodeId == currentEpisodeId

/** Lifecycle of subtitles for the currently displayed podcast episode. */
sealed interface PodcastSubtitleState {
    data object Idle : PodcastSubtitleState
    data object Loading : PodcastSubtitleState

    /** Publisher transcript (or a previously generated on-device transcript) is available. */
    data class Ready(val transcript: EpisodeTranscript) : PodcastSubtitleState

    /** No transcript exists for this episode; on-device recognition can be started manually. */
    data object Unavailable : PodcastSubtitleState

    /** On-device recognition is running; partial cues are already displayable. */
    data class Transcribing(
        val cues: List<SubtitleCue>,
        val progressPercent: Int,
    ) : PodcastSubtitleState

    data class TranscriptionFailed(val message: String) : PodcastSubtitleState
}

sealed interface PodcastTranslationStatus {
    data object Idle : PodcastTranslationStatus
    data object Translating : PodcastTranslationStatus
    data object Success : PodcastTranslationStatus
    data class Error(val message: String) : PodcastTranslationStatus
}

/**
 * Single source of truth for podcast subtitles in the player: resolves feed transcripts,
 * exposes on-device recognition, and produces windowed translations.
 */
@Singleton
class PodcastSubtitleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriptRepository: TranscriptRepository,
    private val podcastRepository: PodcastRepository,
    private val transcriber: OnDeviceTranscriber,
    private val database: com.metrolist.music.db.MusicDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PodcastSubtitleState>(PodcastSubtitleState.Idle)
    val state: StateFlow<PodcastSubtitleState> = _state.asStateFlow()

    private val _translations = MutableStateFlow<Map<Int, String>>(emptyMap())
    val translations: StateFlow<Map<Int, String>> = _translations.asStateFlow()

    private val _modelDownloadProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val modelDownloadProgress: StateFlow<Pair<Long, Long>?> = _modelDownloadProgress.asStateFlow()

    private val _translationStatus = MutableStateFlow<PodcastTranslationStatus>(PodcastTranslationStatus.Idle)
    val translationStatus: StateFlow<PodcastTranslationStatus> = _translationStatus.asStateFlow()

    private var currentEpisodeId: String? = null
    private var episodeGeneration = 0L
    private var translationGeneration = 0L
    private var transcriptLoadJob: Job? = null
    private var transcriptionJob: Job? = null
    private var translationJob: Job? = null
    private var translationConfigKey: String? = null
    private var translationStatusShown = false

    fun isAsrModelReady(): Boolean = transcriber.isModelReady()

    fun asrModelStorageBytes(): Long = transcriber.modelStorageBytes()

    fun asrModelDownloadBytes(): Long = OnDeviceTranscriber.approximateModelDownloadBytes()

    fun deleteAsrModel() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        transcriber.deleteModels()
        _modelDownloadProgress.value = null
    }

    /**
     * Notifies the manager that the displayed episode changed. Called from the player UI;
     * safe to call repeatedly for the same episode.
     */
    fun onEpisodeChanged(metadata: MediaMetadata?) {
        if (metadata == null || !metadata.isEpisode) {
            reset(PodcastSubtitleState.Idle)
            return
        }
        if (metadata.id == currentEpisodeId) {
            TextCacheSignals.protect(CACHE_PROTECTION_OWNER, metadata.id.takeIf { metadata.mediaUrl != null })
            return
        }
        reset(PodcastSubtitleState.Loading)
        currentEpisodeId = metadata.id
        TextCacheSignals.protect(CACHE_PROTECTION_OWNER, metadata.id.takeIf { metadata.mediaUrl != null })
        val episodeId = metadata.id
        val requestGeneration = episodeGeneration

        transcriptLoadJob = scope.launch {
            fun isCurrentRequest() =
                isCurrentSubtitleRequest(requestGeneration, episodeGeneration, episodeId, currentEpisodeId)
            // Prefer a publisher transcript whenever the feed advertises one. A generated
            // transcript is only a fallback, so it must never shadow a later feed refresh.
            var refs = transcriptRepository.transcriptRefs(episodeId)
            if (refs == null) {
                // Subscriptions imported before this feature have no index entry yet;
                // re-parsing the feed records the transcript links, then retry once.
                ensureFeedParsed(metadata)
                refs = transcriptRepository.transcriptRefs(episodeId)
            }

            if (refs != null) {
                val loaded = runCatching { transcriptRepository.loadTranscript(episodeId) }.getOrNull()
                if (!isActive || !isCurrentRequest()) return@launch
                if (loaded != null) {
                    _state.value = PodcastSubtitleState.Ready(loaded)
                    return@launch
                }
            }

            // No usable publisher transcript: restore a previously generated on-device one.
            transcriptRepository.cachedTranscript(episodeId)
                ?.takeIf { it.source == TranscriptSource.ON_DEVICE }
                ?.let { cached ->
                    if (isActive && isCurrentRequest()) _state.value = PodcastSubtitleState.Ready(cached)
                    return@launch
                }

            if (isActive && isCurrentRequest()) {
                _state.value = PodcastSubtitleState.Unavailable
            }
        }
    }

    fun currentCues(): List<SubtitleCue> = when (val current = _state.value) {
        is PodcastSubtitleState.Ready -> current.transcript.cues
        is PodcastSubtitleState.Transcribing -> current.cues
        else -> emptyList()
    }

    /** Downloads and recognizes English speech on-device. */
    fun startTranscription(metadata: MediaMetadata, durationMs: Long) {
        if (transcriptionJob?.isActive == true) return
        val mediaUrl = metadata.mediaUrl ?: return
        val episodeId = metadata.id
        currentEpisodeId = episodeId
        transcriptionJob = scope.launch {
            if (!transcriber.isModelReady()) {
                transcriber.invalidateInvalidModelFiles()
                _state.value = PodcastSubtitleState.Unavailable
                _modelDownloadProgress.value = 0L to asrModelDownloadBytes()
                val downloadResult = transcriber.downloadModels { bytes, total ->
                    _modelDownloadProgress.value = bytes to total
                }
                if (downloadResult.isFailure) {
                    _modelDownloadProgress.value = null
                    if (currentEpisodeId == episodeId) {
                        _state.value = PodcastSubtitleState.TranscriptionFailed(
                            downloadResult.exceptionOrNull()?.message
                                ?: context.getString(R.string.podcast_subtitle_asr_failed),
                        )
                    }
                    return@launch
                }
                // Keep the completed state visible until native recognition starts; do not flash
                // back to an empty download button while the final files are being validated.
                _modelDownloadProgress.value = asrModelDownloadBytes() to
                    asrModelDownloadBytes()
                _modelDownloadProgress.value = null
            }

            _state.value = PodcastSubtitleState.Transcribing(emptyList(), 0)
            val result = transcriber.transcribe(
                source = mediaUrl,
                totalDurationMs = durationMs,
            ) { partial, percent ->
                if (currentEpisodeId == episodeId) {
                    _state.value = PodcastSubtitleState.Transcribing(partial, percent)
                }
            }
            result
                .onSuccess { (cues, language) ->
                    if (currentEpisodeId != episodeId) return@onSuccess
                    if (cues.isEmpty()) {
                        _state.value = PodcastSubtitleState.TranscriptionFailed(
                            context.getString(R.string.podcast_subtitle_asr_empty),
                        )
                        return@onSuccess
                    }
                    transcriptRepository.storeOnDeviceTranscript(episodeId, cues, language)
                    _state.value = PodcastSubtitleState.Ready(
                        EpisodeTranscript(
                            episodeId = episodeId,
                            source = TranscriptSource.ON_DEVICE,
                            cues = cues,
                            language = language,
                        ),
                    )
                }
                .onFailure { error ->
                    if (currentEpisodeId != episodeId || error is kotlinx.coroutines.CancellationException) {
                        return@onFailure
                    }
                    _state.value = PodcastSubtitleState.TranscriptionFailed(
                        error.message ?: context.getString(R.string.podcast_subtitle_asr_failed),
                    )
                }
        }
    }

    fun cancelTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        _modelDownloadProgress.value = null
        if (_state.value is PodcastSubtitleState.Transcribing) {
            _state.value = PodcastSubtitleState.Unavailable
        }
    }

    /**
     * Ensures the lines around [centerIndex] are translated. Persisted translations are used
     * first; missing lines are translated in one batch per window.
     */
    fun requestTranslations(centerIndex: Int, config: SubtitleTranslationConfig) {
        val cues = currentCues()
        if (cues.isEmpty() || !config.hasCredentials) return

        // Skip translating when the transcript already speaks the target language.
        val transcriptLanguage = currentTranscriptLanguage()
        if (transcriptLanguage != null && config.targetLanguage.startsWith(transcriptLanguage)) return

        val windowStart = (centerIndex - 1).coerceAtLeast(0)
        val windowEnd = (centerIndex + TRANSLATION_WINDOW_AHEAD).coerceAtMost(cues.lastIndex)
        if (windowStart > windowEnd) return

        val episodeId = currentEpisodeId ?: return
        val configKey = "${episodeId}|${config.provider}|${config.targetLanguage}|${config.model}"
        if (translationConfigKey != configKey) {
            translationGeneration++
            translationJob?.cancel()
            translationJob = null
            translationConfigKey = configKey
            translationStatusShown = false
            _translations.value = emptyMap()
            _translationStatus.value = PodcastTranslationStatus.Idle
        }
        if (translationJob?.isActive == true) return
        val requestGeneration = translationGeneration
        val showStatus = !translationStatusShown
        if (showStatus) {
            translationStatusShown = true
            _translationStatus.value = PodcastTranslationStatus.Translating
        }
        translationJob = scope.launch {
            fun isCurrentRequest() =
                isCurrentSubtitleRequest(requestGeneration, translationGeneration, episodeId, currentEpisodeId) &&
                    translationConfigKey == configKey

            if (_translations.value.isEmpty()) {
                val persisted = transcriptRepository.loadTranslation(
                    episodeId, config.targetLanguage, config.provider,
                )
                if (!isActive || !isCurrentRequest()) return@launch
                if (persisted.isNotEmpty()) {
                    _translations.value = _translations.value + persisted.filterKeys { it < cues.size }
                }
            }
            val stillMissing = (windowStart..windowEnd).filter { index ->
                _translations.value[index] == null && cues[index].text.isNotBlank()
            }
            if (stillMissing.isEmpty()) {
                if (showStatus) {
                    _translationStatus.value = PodcastTranslationStatus.Success
                    delay(3_000)
                    if (isActive && isCurrentRequest() && _translationStatus.value == PodcastTranslationStatus.Success) {
                        _translationStatus.value = PodcastTranslationStatus.Idle
                    }
                }
                return@launch
            }

            val batches = buildTranslationBatches(stillMissing, cues)
            var failedMessage: String? = null
            for (batch in batches) {
                if (!isActive || !isCurrentRequest()) return@launch
                val result = SubtitleTranslationHelper.translateLines(
                    lines = batch.map { cues[it].text },
                    config = config,
                    context = context,
                )
                // Providers may wrap cancellation in Result; an obsolete reply cannot own UI state.
                if (!isActive || !isCurrentRequest()) return@launch
                result.onSuccess { translatedLines ->
                    val updated = _translations.value.toMutableMap()
                    val forPersistence = mutableMapOf<Int, String>()
                    batch.forEachIndexed { position, index ->
                        val line = translatedLines.getOrNull(position).orEmpty()
                        if (line.isNotBlank()) {
                            updated[index] = line
                            forPersistence[index] = line
                        }
                    }
                    _translations.value = updated
                    if (forPersistence.isNotEmpty()) {
                        transcriptRepository.storeTranslation(
                            episodeId, config.targetLanguage, config.provider, forPersistence,
                        )
                    }
                }.onFailure { error ->
                    failedMessage = error.message
                }
                if (!isActive || !isCurrentRequest()) return@launch
                if (failedMessage != null) break
            }
            if (failedMessage != null) {
                val message = failedMessage
                _translationStatus.value = PodcastTranslationStatus.Error(message)
            } else if (showStatus) {
                _translationStatus.value = PodcastTranslationStatus.Success
                delay(3_000)
                if (isActive && isCurrentRequest() && _translationStatus.value == PodcastTranslationStatus.Success) {
                    _translationStatus.value = PodcastTranslationStatus.Idle
                }
            } else {
                _translationStatus.value = PodcastTranslationStatus.Idle
            }
        }
    }

    fun clearTranslations() {
        translationGeneration++
        translationJob?.cancel()
        translationJob = null
        _translations.value = emptyMap()
        _translationStatus.value = PodcastTranslationStatus.Idle
        translationConfigKey = null
        translationStatusShown = false
    }

    fun reset() {
        reset(PodcastSubtitleState.Idle)
    }

    private suspend fun ensureFeedParsed(metadata: MediaMetadata) {
        val podcastId = metadata.album?.id ?: return
        val feedUrl = runCatching { database.podcastOnce(podcastId)?.feedUrl }.getOrNull() ?: return
        runCatching { podcastRepository.fetchFeed(feedUrl) }
    }

    private fun buildTranslationBatches(
        indices: List<Int>,
        cues: List<SubtitleCue>,
    ): List<List<Int>> {
        val batches = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        var currentChars = 0
        indices.forEach { index ->
            val lineChars = cues[index].text.length
            if (current.isNotEmpty() && currentChars + lineChars > MAX_TRANSLATION_CHARS) {
                batches += current
                current = mutableListOf()
                currentChars = 0
            }
            current += index
            currentChars += lineChars
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun currentTranscriptLanguage(): String? = when (val current = _state.value) {
        is PodcastSubtitleState.Ready -> current.transcript.language
        else -> null
    }

    private fun reset(next: PodcastSubtitleState) {
        episodeGeneration++
        transcriptLoadJob?.cancel()
        transcriptLoadJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        _modelDownloadProgress.value = null
        clearTranslations()
        currentEpisodeId = null
        TextCacheSignals.protect(CACHE_PROTECTION_OWNER, null)
        _state.value = next
    }

    private companion object {
        const val CACHE_PROTECTION_OWNER = "podcast_subtitles"
        const val TRANSLATION_WINDOW_AHEAD = 80
        const val MAX_TRANSLATION_CHARS = 12_000
    }
}

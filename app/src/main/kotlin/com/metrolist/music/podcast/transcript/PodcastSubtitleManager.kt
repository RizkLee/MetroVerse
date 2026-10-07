/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import android.net.Uri
import com.metrolist.music.R
import com.metrolist.music.cache.TextCacheSignals
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.podcast.PodcastRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _asrModelReady = MutableStateFlow(false)
    val asrModelReady: StateFlow<Boolean> = _asrModelReady.asStateFlow()
    private val _modelOperationBusy = MutableStateFlow(false)
    val modelOperationBusy: StateFlow<Boolean> = _modelOperationBusy.asStateFlow()
    private val _modelDownloadError = MutableStateFlow<String?>(null)
    val modelDownloadError: StateFlow<String?> = _modelDownloadError.asStateFlow()
    @Volatile private var modelStorageBytes = 0L
    private var modelOperationJob: Job? = null

    private val _translationStatus = MutableStateFlow<PodcastTranslationStatus>(PodcastTranslationStatus.Idle)
    val translationStatus: StateFlow<PodcastTranslationStatus> = _translationStatus.asStateFlow()

    @Volatile private var currentEpisodeId: String? = null
    @Volatile private var episodeGeneration = 0L
    private var translationGeneration = 0L
    private var transcriptLoadJob: Job? = null
    private var transcriptionJob: Job? = null
    private var translationJob: Job? = null
    private var translationConfigKey: String? = null
    private var translationStatusShown = false

    init {
        _modelOperationBusy.value = true
        modelOperationJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                refreshModelState()
            } finally {
                _modelOperationBusy.value = false
            }
        }
    }

    fun isAsrModelReady(): Boolean = _asrModelReady.value

    fun asrModelStorageBytes(): Long = modelStorageBytes

    fun asrModelDownloadBytes(): Long = OnDeviceTranscriber.approximateModelDownloadBytes()

    fun deleteAsrModel() {
        if (!_modelOperationBusy.compareAndSet(false, true)) return
        val activeTranscription = transcriptionJob
        _modelDownloadError.value = null
        modelOperationJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                activeTranscription?.cancelAndJoin()
                transcriptionJob = null
                if (_state.value is PodcastSubtitleState.Transcribing) _state.value = PodcastSubtitleState.Unavailable
                val deleted = withContext(Dispatchers.IO) { transcriber.deleteModels() }
                if (!deleted) _modelDownloadError.value = context.getString(R.string.podcast_subtitle_asr_model_failed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _modelDownloadError.value = context.getString(R.string.podcast_subtitle_asr_model_failed)
            } finally {
                _modelDownloadProgress.value = null
                withContext(NonCancellable) {
                    refreshModelState()
                    _modelOperationBusy.value = false
                }
            }
        }
    }

    fun downloadAsrModel() = prepareModel()

    fun importAsrModel(treeUri: Uri) = prepareModel(treeUri = treeUri)

    fun clearModelDownloadError() { _modelDownloadError.value = null }

    private suspend fun refreshModelState() {
        val status = withContext(Dispatchers.IO) {
            runCatching { transcriber.isModelReady() to transcriber.modelStorageBytes() }.getOrDefault(false to 0L)
        }
        _asrModelReady.value = status.first
        modelStorageBytes = status.second
    }

    private fun prepareModel(
        treeUri: Uri? = null,
        onPrepared: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null,
    ) {
        if (_asrModelReady.value) { onPrepared?.invoke(); return }
        if (!_modelOperationBusy.compareAndSet(false, true)) return
        _modelDownloadError.value = null
        _modelDownloadProgress.value = 0L to asrModelDownloadBytes()
        modelOperationJob = scope.launch(Dispatchers.Main.immediate) {
            var prepared = false
            try {
                val progress: (Long, Long) -> Unit = { bytes, total -> _modelDownloadProgress.value = bytes to total }
                val result = withContext(Dispatchers.IO) {
                    if (treeUri == null) transcriber.downloadModels(progress)
                    else transcriber.importModels(treeUri, progress)
                }
                prepared = result.isSuccess
                if (result.isFailure) {
                    val failure = result.exceptionOrNull()
                    if (failure is CancellationException) throw failure
                    val message = failure?.message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.podcast_subtitle_asr_model_failed)
                    _modelDownloadError.value = message
                    onFailure?.invoke(message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                val message = context.getString(R.string.podcast_subtitle_asr_model_failed)
                _modelDownloadError.value = message
                onFailure?.invoke(message)
            } finally {
                _modelDownloadProgress.value = null
                withContext(NonCancellable) {
                    refreshModelState()
                    _modelOperationBusy.value = false
                }
            }
            if (prepared) {
                if (_asrModelReady.value) {
                    onPrepared?.invoke()
                } else {
                    val message = context.getString(R.string.podcast_subtitle_asr_model_failed)
                    _modelDownloadError.value = message
                    onFailure?.invoke(message)
                }
            }
        }
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
        if (transcriptionJob?.isActive == true || _modelOperationBusy.value) return
        val mediaUrl = metadata.mediaUrl ?: return
        val episodeId = metadata.id
        if (!_asrModelReady.value) {
            val requestedGeneration = episodeGeneration
            prepareModel(
                onPrepared = {
                    if (isCurrentSubtitleRequest(requestedGeneration, episodeGeneration, episodeId, currentEpisodeId)) {
                        startTranscription(metadata, durationMs)
                    }
                },
                onFailure = { message ->
                    if (isCurrentSubtitleRequest(requestedGeneration, episodeGeneration, episodeId, currentEpisodeId)) {
                        _state.value = PodcastSubtitleState.TranscriptionFailed(message)
                    }
                },
            )
            return
        }
        transcriptLoadJob?.cancel()
        transcriptLoadJob = null
        episodeGeneration++
        val requestedGeneration = episodeGeneration
        currentEpisodeId = episodeId
        TextCacheSignals.protect(CACHE_PROTECTION_OWNER, episodeId)
        clearTranslations()
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            fun currentRequest() = isActive &&
                isCurrentSubtitleRequest(requestedGeneration, episodeGeneration, episodeId, currentEpisodeId)
            _state.value = PodcastSubtitleState.Transcribing(emptyList(), 0)
            try {
                val result = transcriber.transcribe(mediaUrl, durationMs) { partial, percent ->
                    if (currentRequest()) _state.value = PodcastSubtitleState.Transcribing(partial, percent)
                }
                if (!currentRequest()) return@launch
                result.onSuccess { (cues, language) ->
                    if (cues.isEmpty()) {
                        _state.value = PodcastSubtitleState.TranscriptionFailed(
                            context.getString(R.string.podcast_subtitle_asr_empty),
                        )
                        return@onSuccess
                    }
                    transcriptRepository.storeOnDeviceTranscript(episodeId, cues, language)
                    if (!currentRequest()) return@onSuccess
                    _state.value = PodcastSubtitleState.Ready(
                        EpisodeTranscript(
                            episodeId = episodeId,
                            source = TranscriptSource.ON_DEVICE,
                            cues = cues,
                            language = language,
                        ),
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.value = PodcastSubtitleState.TranscriptionFailed(
                        error.message ?: context.getString(R.string.podcast_subtitle_asr_failed),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentRequest()) _state.value = PodcastSubtitleState.TranscriptionFailed(
                    context.getString(R.string.podcast_subtitle_asr_failed),
                )
            }
        }
        transcriptionJob = job
        job.start()
    }

    fun cancelTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        if (_modelDownloadProgress.value != null) modelOperationJob?.cancel()
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

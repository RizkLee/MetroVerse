/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.podcast.PodcastRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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

    private var currentEpisodeId: String? = null
    private var transcriptionJob: Job? = null
    private var translationJob: Job? = null

    fun isAsrModelReady(): Boolean = transcriber.isModelReady()

    /**
     * Notifies the manager that the displayed episode changed. Called from the player UI;
     * safe to call repeatedly for the same episode.
     */
    fun onEpisodeChanged(metadata: MediaMetadata?) {
        if (metadata == null || !metadata.isEpisode) {
            reset(PodcastSubtitleState.Idle)
            return
        }
        if (metadata.id == currentEpisodeId) return
        reset(PodcastSubtitleState.Loading)
        currentEpisodeId = metadata.id
        val episodeId = metadata.id

        scope.launch {
            // 1. Cached transcript (feed or previously generated on-device one).
            transcriptRepository.cachedTranscript(episodeId)?.let { cached ->
                if (currentEpisodeId == episodeId) _state.value = PodcastSubtitleState.Ready(cached)
                return@launch
            }

            // 2. Feed transcript advertised by podcast:transcript.
            var refs = transcriptRepository.transcriptRefs(episodeId)
            if (refs == null) {
                // Subscriptions imported before this feature have no index entry yet;
                // re-parsing the feed records the transcript links, then retry once.
                ensureFeedParsed(metadata)
                refs = transcriptRepository.transcriptRefs(episodeId)
            }
            if (refs == null) {
                if (currentEpisodeId == episodeId) {
                    _state.value = PodcastSubtitleState.Unavailable
                }
                return@launch
            }

            val loaded = runCatching { transcriptRepository.loadTranscript(episodeId) }.getOrNull()
            if (currentEpisodeId != episodeId) return@launch
            _state.value = when {
                loaded != null -> PodcastSubtitleState.Ready(loaded)
                else -> PodcastSubtitleState.Unavailable
            }
        }
    }

    fun currentCues(): List<SubtitleCue> = when (val current = _state.value) {
        is PodcastSubtitleState.Ready -> current.transcript.cues
        is PodcastSubtitleState.Transcribing -> current.cues
        else -> emptyList()
    }

    /** Starts manual on-device recognition for the given episode. */
    fun startTranscription(metadata: MediaMetadata, durationMs: Long, languageMode: String) {
        if (transcriptionJob?.isActive == true) return
        val mediaUrl = metadata.mediaUrl ?: return
        val episodeId = metadata.id
        currentEpisodeId = episodeId
        transcriptionJob = scope.launch {
            // A corrupt model would abort the process inside native code; re-fetch instead.
            if (transcriber.invalidateInvalidModelFiles()) {
                _state.value = PodcastSubtitleState.TranscriptionFailed(
                    context.getString(R.string.podcast_subtitle_asr_model_corrupt),
                )
                return@launch
            }
            _state.value = PodcastSubtitleState.Transcribing(emptyList(), 0)
            val result = transcriber.transcribe(
                source = mediaUrl,
                languageMode = languageMode,
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
        if (_state.value is PodcastSubtitleState.Transcribing) {
            _state.value = PodcastSubtitleState.Unavailable
        }
    }

    fun downloadAsrModel(onDone: (Result<Unit>) -> Unit) {
        if (_modelDownloadProgress.value != null) return
        scope.launch {
            val result = transcriber.downloadModels { bytes, total ->
                _modelDownloadProgress.value = bytes to total
            }
            _modelDownloadProgress.value = null
            if (result.isFailure && currentEpisodeId != null) {
                _state.value = PodcastSubtitleState.TranscriptionFailed(
                    result.exceptionOrNull()?.message
                        ?: context.getString(R.string.podcast_subtitle_asr_failed),
                )
            }
            onDone(result)
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
        if (translationJob?.isActive == true) return
        translationJob = scope.launch {
            if (_translations.value.isEmpty()) {
                val persisted = transcriptRepository.loadTranslation(
                    episodeId, config.targetLanguage, config.provider,
                )
                if (persisted.isNotEmpty()) {
                    _translations.value = _translations.value + persisted.filterKeys { it < cues.size }
                }
            }
            val stillMissing = (windowStart..windowEnd).filter { index ->
                _translations.value[index] == null && cues[index].text.isNotBlank()
            }
            if (stillMissing.isEmpty()) return@launch

            val result = SubtitleTranslationHelper.translateLines(
                lines = stillMissing.map { cues[it].text },
                config = config,
                context = context,
            )
            result.onSuccess { translatedLines ->
                val updated = _translations.value.toMutableMap()
                val forPersistence = mutableMapOf<Int, String>()
                stillMissing.forEachIndexed { position, index ->
                    val line = translatedLines.getOrNull(position).orEmpty()
                    updated[index] = line
                    forPersistence[index] = line
                }
                _translations.value = updated
                transcriptRepository.storeTranslation(
                    episodeId, config.targetLanguage, config.provider, forPersistence,
                )
            }
        }
    }

    fun clearTranslations() {
        translationJob?.cancel()
        translationJob = null
        _translations.value = emptyMap()
    }

    fun reset() {
        reset(PodcastSubtitleState.Idle)
    }

    private suspend fun ensureFeedParsed(metadata: MediaMetadata) {
        val podcastId = metadata.album?.id ?: return
        val feedUrl = runCatching { database.podcastOnce(podcastId)?.feedUrl }.getOrNull() ?: return
        runCatching { podcastRepository.fetchFeed(feedUrl) }
    }

    private fun currentTranscriptLanguage(): String? = when (val current = _state.value) {
        is PodcastSubtitleState.Ready -> current.transcript.language
        else -> null
    }

    private fun reset(next: PodcastSubtitleState) {
        transcriptionJob?.cancel()
        transcriptionJob = null
        translationJob?.cancel()
        translationJob = null
        _translations.value = emptyMap()
        currentEpisodeId = null
        _state.value = next
    }

    private companion object {
        const val TRANSLATION_WINDOW_AHEAD = 5
    }
}

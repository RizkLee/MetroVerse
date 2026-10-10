/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.metrolist.music.BuildConfig
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.util.concurrent.atomic.AtomicReference
import timber.log.Timber
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

    data class Partial(
        val transcript: EpisodeTranscript,
        val message: String? = null,
        val hasPendingSave: Boolean = false,
        val canResume: Boolean = true,
    ) : PodcastSubtitleState

    /** No transcript exists for this episode; on-device recognition can be started manually. */
    data object Unavailable : PodcastSubtitleState

    /** On-device recognition is running; partial cues are already displayable. */
    data class Transcribing(
        val cues: List<SubtitleCue>,
        val progressPercent: Int,
        val phase: AsrRecognitionPhase = AsrRecognitionPhase.RECOGNIZING,
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
    private var transcriptLoadJob: Job? = null
    private var transcriptionJob: Job? = null
    private var currentCheckpoint: EpisodeTranscript? = null
    private var currentMediaSourceFingerprint: String? = null
    private val seekRequests = AsrSeekRequests(SystemClock::elapsedRealtime)
    private val translationCoordinator = SubtitleTranslationCoordinator(
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate),
        load = transcriptRepository::loadTranslation,
        store = transcriptRepository::storeTranslation,
        translate = { lines, config -> SubtitleTranslationHelper.translateLines(lines, config, context) },
        onTranslations = { _translations.value = it },
        onStatus = { _translationStatus.value = it },
        errorMessage = { context.getString(R.string.ai_error_translation_failed) },
        saveErrorMessage = { context.getString(R.string.podcast_subtitle_save_failed) },
        hasPendingSave = transcriptRepository::hasPendingTranslationSave,
        onWorkEpisodeChanged = { TextCacheSignals.protect(TRANSLATION_CACHE_OWNER, it) },
        trace = { message -> if (BuildConfig.DEBUG) Timber.tag("PodcastSubtitleTranslation").d(message) },
    )

    init {
        _modelOperationBusy.value = true
        modelOperationJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                runCatching { transcriber.removeRetiredModelFiles() }.onFailure { error ->
                    if (error is CancellationException) throw error
                }
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
                if (transcriptionJob == activeTranscription) transcriptionJob = null
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
            currentCoroutineContext().ensureActive()
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
        val sourceFingerprint = metadata.mediaUrl?.let { subtitleCacheFingerprint("asr-source-v1", it) }
        if (metadata.id == currentEpisodeId && sourceFingerprint == currentMediaSourceFingerprint) {
            TextCacheSignals.protect(CACHE_PROTECTION_OWNER, metadata.id.takeIf { metadata.mediaUrl != null })
            return
        }
        val previousTranscription = transcriptionJob
        reset(PodcastSubtitleState.Loading)
        currentEpisodeId = metadata.id
        currentMediaSourceFingerprint = sourceFingerprint
        TextCacheSignals.protect(CACHE_PROTECTION_OWNER, metadata.id.takeIf { metadata.mediaUrl != null })
        val episodeId = metadata.id
        val requestGeneration = episodeGeneration

        transcriptLoadJob = scope.launch(Dispatchers.Main.immediate) {
            fun isCurrentRequest() =
                isCurrentSubtitleRequest(requestGeneration, episodeGeneration, episodeId, currentEpisodeId)
            // A quick return to the previous episode must see its cancellation checkpoint.
            previousTranscription?.join()
            if (!isActive || !isCurrentRequest()) return@launch
            var refs = transcriptRepository.transcriptRefs(episodeId)
            if (refs == null) {
                ensureFeedParsed(metadata)
                refs = transcriptRepository.transcriptRefs(episodeId)
            }
            if (refs != null) {
                val loaded = runCatching { transcriptRepository.loadTranscript(episodeId) }.getOrNull()
                if (!isActive || !isCurrentRequest()) return@launch
                if (loaded != null) {
                    showTranscript(loaded)
                    return@launch
                }
            }
            val pendingSave = transcriptRepository.pendingTranscript(episodeId)?.takeIf { it.mediaSourceFingerprint == sourceFingerprint }
            if (!isActive || !isCurrentRequest()) return@launch
            if (pendingSave != null) {
                showInterruptedTranscript(pendingSave, context.getString(R.string.podcast_subtitle_save_failed), hasPendingSave = true)
                return@launch
            }
            val cached = transcriptRepository.cachedTranscript(episodeId)?.takeIf {
                it.source == TranscriptSource.ON_DEVICE &&
                    (it.mediaSourceFingerprint == sourceFingerprint || (it.isComplete && it.mediaSourceFingerprint == null))
            }
            if (!isActive || !isCurrentRequest()) return@launch
            if (cached != null) showTranscript(cached)
            else _state.value = PodcastSubtitleState.Unavailable
        }
    }

    fun currentCues(): List<SubtitleCue> = when (val current = _state.value) {
        is PodcastSubtitleState.Ready -> current.transcript.cues
        is PodcastSubtitleState.Partial -> current.transcript.cues
        is PodcastSubtitleState.Transcribing -> current.cues
        else -> emptyList()
    }

    private fun showTranscript(transcript: EpisodeTranscript, message: String? = null) {
        currentCheckpoint = transcript.takeIf { it.source == TranscriptSource.ON_DEVICE }
        _state.value = if (transcript.isComplete) PodcastSubtitleState.Ready(transcript)
        else PodcastSubtitleState.Partial(transcript, message, canResume = transcript.recognizerFingerprint == OnDeviceTranscriber.checkpointIdentity())
        translationCoordinator.updateCues(transcript.cues, isStreaming = false)
    }

    private fun showInterruptedTranscript(transcript: EpisodeTranscript, message: String? = null, hasPendingSave: Boolean = false) {
        currentCheckpoint = transcript
        _state.value = PodcastSubtitleState.Partial(
            transcript, message, hasPendingSave,
            canResume = transcript.recognizerFingerprint == OnDeviceTranscriber.checkpointIdentity(),
        )
        translationCoordinator.updateCues(transcript.cues, isStreaming = false)
    }

    /** Downloads and recognizes English speech on-device. */
    fun startTranscription(metadata: MediaMetadata, durationMs: Long, positionProvider: () -> Long = { 0L }) {
        if (transcriptionJob?.isActive == true) return
        val pendingSave = (_state.value as? PodcastSubtitleState.Partial)?.takeIf {
            it.transcript.episodeId == metadata.id && (it.hasPendingSave || it.transcript.isComplete)
        }?.transcript
        if (pendingSave != null) {
            retryTranscriptSave(pendingSave)
            return
        }
        if (_modelOperationBusy.value) return
        val mediaUrl = metadata.mediaUrl ?: return
        val episodeId = metadata.id
        if (!_asrModelReady.value) {
            val requestedGeneration = episodeGeneration
            prepareModel(
                onPrepared = {
                    if (isCurrentSubtitleRequest(requestedGeneration, episodeGeneration, episodeId, currentEpisodeId)) {
                        startTranscription(metadata, durationMs, positionProvider)
                    }
                },
                onFailure = { message ->
                    if (isCurrentSubtitleRequest(requestedGeneration, episodeGeneration, episodeId, currentEpisodeId)) {
                        val saved = currentCheckpoint?.takeIf { it.cues.isNotEmpty() && !it.isComplete }
                        if (saved != null) showTranscript(saved, message)
                        else _state.value = PodcastSubtitleState.TranscriptionFailed(message)
                    }
                },
            )
            return
        }
        val previousTranscription = transcriptionJob
        val previousCheckpoint = currentCheckpoint?.takeIf { it.episodeId == episodeId && !it.isComplete }
        transcriptLoadJob?.cancel()
        transcriptLoadJob = null
        episodeGeneration++
        val requestedGeneration = episodeGeneration
        val sourceFingerprint = subtitleCacheFingerprint("asr-source-v1", mediaUrl)
        val recognizerFingerprint = OnDeviceTranscriber.checkpointIdentity()
        seekRequests.clear()
        currentEpisodeId = episodeId
        currentMediaSourceFingerprint = sourceFingerprint
        TextCacheSignals.protect(CACHE_PROTECTION_OWNER, episodeId)
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val ownerJob = currentCoroutineContext().job
            fun ownsRequest() =
                isCurrentSubtitleRequest(requestedGeneration, episodeGeneration, episodeId, currentEpisodeId)
            withContext(NonCancellable) { previousTranscription?.join() }
            currentCoroutineContext().ensureActive()
            if (!isActive || !ownsRequest()) return@launch
            val recovered = transcriptRepository.pendingTranscript(episodeId)?.takeIf {
                    !it.isComplete && it.mediaSourceFingerprint == sourceFingerprint
                }
                ?: transcriptRepository.cachedTranscript(episodeId)?.takeIf {
                    !it.isComplete && it.mediaSourceFingerprint == sourceFingerprint
                }
                ?: previousCheckpoint?.takeIf { it.mediaSourceFingerprint == sourceFingerprint }
            currentCoroutineContext().ensureActive()
            if (!ownsRequest()) return@launch
            val recovery = recoverAsrProgress(recovered, sourceFingerprint, recognizerFingerprint, positionProvider())
            val initial = EpisodeTranscript(
                episodeId, TranscriptSource.ON_DEVICE, recovery.cues, "en",
                isComplete = false,
                progressPercent = if (recovery.reason == "saved_checkpoint") recovered?.progressPercent ?: 0 else 0,
                resumePositionMs = recovery.positionMs,
                mediaSourceFingerprint = sourceFingerprint,
                recognizerFingerprint = recognizerFingerprint,
                processedRanges = recovery.ranges,
                audioDurationMs = recovered?.audioDurationMs.takeIf { recovery.reason == "saved_checkpoint" },
            )
            val latest = AtomicReference(initial)
            currentCheckpoint = initial
            _state.value = PodcastSubtitleState.Transcribing(initial.cues, initial.progressPercent, AsrRecognitionPhase.PREPARING)
            translationCoordinator.updateCues(initial.cues, isStreaming = true)
            var completed = false
            var failureMessage: String? = null
            TextCacheSignals.protect(TRANSCRIPTION_CACHE_OWNER, episodeId)
            coroutineScope {
                val displayUpdates = Channel<Pair<EpisodeTranscript, AsrRecognitionPhase>>(Channel.CONFLATED)
                val checkpointUpdates = Channel<Unit>(Channel.CONFLATED)
                val displayJob = launch(Dispatchers.Main.immediate) {
                    for ((snapshot, phase) in displayUpdates) {
                        if (ownerJob.isActive && ownsRequest()) {
                            currentCheckpoint = snapshot
                            _state.value = PodcastSubtitleState.Transcribing(snapshot.cues, snapshot.progressPercent, phase)
                            translationCoordinator.updateCues(snapshot.cues, isStreaming = true)
                        }
                    }
                }
                val checkpointJob = launch(Dispatchers.IO) {
                    var nextWriteAt = 0L
                    var saved = recovered
                    for (signal in checkpointUpdates) {
                        val remaining = nextWriteAt - SystemClock.elapsedRealtime()
                        if (remaining > 0) delay(remaining)
                        val snapshot = latest.get()
                        if ((snapshot.cues.isEmpty() && snapshot.processedRanges.isNullOrEmpty()) || snapshot == saved) continue
                        nextWriteAt = SystemClock.elapsedRealtime() + CHECKPOINT_INTERVAL_MS
                        if (transcriptRepository.storeOnDeviceTranscript(snapshot).isSuccess) saved = snapshot
                    }
                }
                try {
                    val result = transcriber.transcribe(
                        source = mediaUrl,
                        totalDurationMs = durationMs,
                        resumePositionMs = recovery.positionMs,
                        retainedCues = recovery.cues,
                        processedRanges = recovery.ranges,
                        recoveryReason = recovery.reason,
                        pollSeek = seekRequests::poll,
                    ) { partial ->
                        // This job also owns its final cancellation checkpoint. UI ownership may
                        // have moved to another episode while its native inference was finishing.
                        val incoming = initial.copy(
                            cues = partial.cues,
                            progressPercent = partial.progressPercent.coerceIn(0, 99),
                            resumePositionMs = partial.resumePositionMs,
                            processedRanges = partial.processedRanges,
                            audioDurationMs = partial.audioDurationMs,
                        )
                        val snapshot = mergeSubtitleCheckpoint(latest.get(), incoming)
                        latest.set(snapshot)
                        val phase = if (partial.phase == AsrRecognitionPhase.RECOGNIZING &&
                            partial.progressPercent < initial.progressPercent
                        ) AsrRecognitionPhase.RESUMING else partial.phase
                        if (ownerJob.isActive && ownsRequest()) {
                            displayUpdates.trySend(snapshot to phase)
                            checkpointUpdates.trySend(Unit)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!ownsRequest()) return@coroutineScope
                    val recognized = result.getOrThrow()
                    val cues = recognized.cues
                    if (cues.isEmpty()) {
                        failureMessage = context.getString(R.string.podcast_subtitle_asr_empty)
                        return@coroutineScope
                    }
                    displayJob.cancelAndJoin()
                    checkpointJob.cancelAndJoin()
                    val finished = latest.get().copy(
                        cues = cues, language = "en", isComplete = true, progressPercent = 100,
                        processedRanges = recognized.processedRanges, audioDurationMs = recognized.audioDurationMs,
                    )
                    latest.set(finished)
                    val persisted = withContext(NonCancellable) { transcriptRepository.storeOnDeviceTranscript(finished).isSuccess }
                    if (!persisted) {
                        failureMessage = context.getString(R.string.podcast_subtitle_save_failed)
                        return@coroutineScope
                    }
                    completed = true
                    if (ownsRequest()) showTranscript(finished)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: AsrRecordingChangedException) {
                    failureMessage = context.getString(R.string.podcast_subtitle_audio_changed)
                    latest.set(latest.get().copy(
                        recognizerFingerprint = "requires-regeneration", processedRanges = emptyList(),
                        resumePositionMs = 0, progressPercent = 0,
                    ))
                } catch (_: Exception) {
                    failureMessage = context.getString(R.string.podcast_subtitle_asr_failed)
                } finally {
                    withContext(NonCancellable) {
                        displayJob.cancelAndJoin()
                        checkpointJob.cancelAndJoin()
                        displayUpdates.close()
                        checkpointUpdates.close()
                        if (!completed) {
                            val snapshot = latest.get()
                            val persisted = (snapshot.cues.isEmpty() && snapshot.processedRanges.isNullOrEmpty()) ||
                                (!snapshot.isComplete && transcriptRepository.storeOnDeviceTranscript(snapshot).isSuccess)
                            if (ownsRequest()) {
                                if (snapshot.cues.isNotEmpty() || !snapshot.processedRanges.isNullOrEmpty()) {
                                    showInterruptedTranscript(
                                        snapshot,
                                        if (persisted) failureMessage else context.getString(R.string.podcast_subtitle_save_failed),
                                        hasPendingSave = !persisted,
                                    )
                                } else {
                                    _state.value = failureMessage?.let { PodcastSubtitleState.TranscriptionFailed(it) }
                                        ?: PodcastSubtitleState.Unavailable
                                }
                            }
                        }
                        if (transcriptionJob == ownerJob) transcriptionJob = null
                        TextCacheSignals.protect(TRANSCRIPTION_CACHE_OWNER, null)
                    }
                }
            }
        }
        transcriptionJob = job
        job.start()
    }

    private fun retryTranscriptSave(transcript: EpisodeTranscript) {
        val previousTranscription = transcriptionJob
        episodeGeneration++
        val requestGeneration = episodeGeneration
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext().job
            var ownsProtection = false
            try {
                withContext(NonCancellable) { previousTranscription?.join() }
                currentCoroutineContext().ensureActive()
                TextCacheSignals.protect(TRANSCRIPTION_CACHE_OWNER, transcript.episodeId)
                ownsProtection = true
                val saved = withContext(NonCancellable) { transcriptRepository.storeOnDeviceTranscript(transcript).isSuccess }
                if (isCurrentSubtitleRequest(requestGeneration, episodeGeneration, transcript.episodeId, currentEpisodeId)) {
                    if (saved) showTranscript(transcript)
                    else showInterruptedTranscript(transcript, context.getString(R.string.podcast_subtitle_save_failed), hasPendingSave = true)
                }
            } finally {
                if (transcriptionJob == owner) transcriptionJob = null
                if (ownsProtection) TextCacheSignals.protect(TRANSCRIPTION_CACHE_OWNER, null)
            }
        }
        transcriptionJob = job
        job.start()
    }

    fun cancelTranscription() {
        seekRequests.clear()
        transcriptionJob?.cancel()
        if (_modelDownloadProgress.value != null) modelOperationJob?.cancel()
        if (_state.value is PodcastSubtitleState.Transcribing) {
            val saved = currentCheckpoint?.takeIf { it.cues.isNotEmpty() }
            if (saved != null) showInterruptedTranscript(saved)
            else _state.value = PodcastSubtitleState.Unavailable
        }
    }

    fun onPlaybackSeek(episodeId: String, previousPositionMs: Long, positionMs: Long) {
        if (episodeId == currentEpisodeId && transcriptionJob?.isActive == true) {
            seekRequests.offer(previousPositionMs, positionMs)
        }
    }

    fun requestTranslations(centerIndex: Int, config: SubtitleTranslationConfig) {
        val cues = currentCues()
        val language = currentTranscriptLanguage()
        if (cues.isEmpty() || !config.hasCredentials || (language != null && config.targetLanguage.startsWith(language))) {
            stopTranslationRequests()
            return
        }
        val episodeId = currentEpisodeId ?: return
        translationCoordinator.request(episodeId, centerIndex, cues, config, isStreaming = _state.value is PodcastSubtitleState.Transcribing)
    }

    fun stopTranslationRequests() = translationCoordinator.stop()

    fun retryTranslations() = translationCoordinator.retry()

    fun clearTranslations() = translationCoordinator.clear()

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
        is PodcastSubtitleState.Partial -> current.transcript.language
        is PodcastSubtitleState.Transcribing -> currentCheckpoint?.language
        else -> null
    }

    private fun reset(next: PodcastSubtitleState) {
        seekRequests.clear()
        episodeGeneration++
        transcriptLoadJob?.cancel()
        transcriptLoadJob = null
        transcriptionJob?.cancel()
        clearTranslations()
        currentEpisodeId = null
        currentCheckpoint = null
        currentMediaSourceFingerprint = null
        TextCacheSignals.protect(CACHE_PROTECTION_OWNER, null)
        _state.value = next
    }

    private companion object {
        const val CACHE_PROTECTION_OWNER = "podcast_subtitles"
        const val TRANSCRIPTION_CACHE_OWNER = "podcast_subtitle_transcription"
        const val TRANSLATION_CACHE_OWNER = "podcast_subtitle_translation"
        const val CHECKPOINT_INTERVAL_MS = 15_000L
    }
}

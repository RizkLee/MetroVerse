/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.metrolist.music.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Uses the streaming recognizer without retaining decoded audio for the entire episode. */
@Singleton
class OnDeviceTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modelStores = AsrModel.all.associateWith { model ->
        AsrModelStore(context, model.directory, MoonshineModelFiles.artifacts(model))
    }

    private fun store(language: AsrLanguage, variant: AsrModelVariant) = modelStores.getValue(language.model(variant))

    fun isModelReady(language: AsrLanguage = AsrLanguage.ENGLISH, variant: AsrModelVariant = language.modelVariant): Boolean = store(language, variant).isReady()
    fun modelStorageBytes(language: AsrLanguage = AsrLanguage.ENGLISH, variant: AsrModelVariant = language.modelVariant): Long = store(language, variant).storageBytes()
    suspend fun deleteModels(language: AsrLanguage = AsrLanguage.ENGLISH, variant: AsrModelVariant = language.modelVariant): Boolean = store(language, variant).delete()
    suspend fun invalidateInvalidModelFiles(language: AsrLanguage = AsrLanguage.ENGLISH, variant: AsrModelVariant = language.modelVariant): Boolean = store(language, variant).invalidateInvalidFiles()
    suspend fun downloadModels(language: AsrLanguage = AsrLanguage.ENGLISH, onProgress: (Long, Long) -> Unit, variant: AsrModelVariant = language.modelVariant): Result<Unit> =
        store(language, variant).download(onProgress)
    suspend fun importModels(treeUri: Uri, language: AsrLanguage = AsrLanguage.ENGLISH, onProgress: (Long, Long) -> Unit, variant: AsrModelVariant = language.modelVariant): Result<Unit> =
        store(language, variant).importFromTree(treeUri, onProgress)

    suspend fun removeRetiredModelFiles() = withContext(Dispatchers.IO) {
        removeRetiredAsrModelFiles(context.filesDir)
    }

    internal suspend fun transcribe(
        source: String,
        totalDurationMs: Long,
        language: AsrLanguage = AsrLanguage.ENGLISH,
        variant: AsrModelVariant = language.modelVariant,
        resumePositionMs: Long = 0L,
        retainedCues: List<SubtitleCue> = emptyList(),
        processedRanges: List<AsrAudioRange> = emptyList(),
        recoveryReason: String = "fresh",
        pollSeek: () -> AsrSeekTarget? = { null },
        onPartial: (AsrRecognitionSnapshot) -> Unit,
    ): Result<AsrRecognitionSnapshot> = withContext(Dispatchers.Default) {
        try {
            val result = store(language, variant).withModel { directory ->
                val diagnostics = AsrDiagnostics()
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()
                val plan = AsrRecognitionPlan(processedRanges)
                var savedCues = retainedCues.sortedBy { it.startMs }
                var totalSamples = resumePositionMs * (SAMPLE_RATE / 1_000)
                var safeResumeMs = resumePositionMs
                var durationHintMs = totalDurationMs
                var streamOwnedStartMs = resumePositionMs
                var transcript: AsrTranscriptAccumulator? = null
                var stream = -1
                var lastStableCues: List<SubtitleCue>? = null
                var visibleCues = savedCues
                diagnostics.resume(resumePositionMs, processedRanges.sumOf { it.endMs - it.startMs }, retainedCues.size, recoveryReason)
                val handle = MoonshineNative.create(directory.absolutePath, variant.nativeArchitecture)
                var publishCheckpoint: (() -> Unit)? = null

                try {
                    diagnostics.record("model_loaded")
                    fun merge(lines: Array<MoonshineLine>) {
                        val current = checkNotNull(transcript)
                        lines.forEach { current.update(it.id, it.text, it.startTimeSeconds, it.durationSeconds, it.complete) }
                    }

                    fun snapshot(phase: AsrRecognitionPhase = AsrRecognitionPhase.RECOGNIZING): AsrRecognitionSnapshot {
                        val stable = transcript?.snapshot(totalSamples)
                        if (stable !== lastStableCues) {
                            if (stable != null) {
                                safeResumeMs = plan.recordFinalized(
                                    streamOwnedStartMs, totalSamples / (SAMPLE_RATE / 1_000), stable,
                                )
                            }
                            visibleCues = if (stable == null) savedCues else replaceAsrRangeCues(
                                savedCues, stable, streamOwnedStartMs, safeResumeMs,
                            )
                            lastStableCues = stable
                        }
                        val endMs = totalSamples / (SAMPLE_RATE / 1_000)
                        val activeRange = if (transcript != null && endMs > streamOwnedStartMs) {
                            AsrAudioRange(streamOwnedStartMs, endMs)
                        } else null
                        return AsrRecognitionSnapshot(
                            visibleCues, plan.processedRanges, safeResumeMs,
                            plan.progress(durationHintMs, activeRange), plan.audioEndMs, phase,
                        )
                    }

                    publishCheckpoint = { onPartial(snapshot()) }

                    fun finishCurrentStream(endMs: Long, finalizeIncomplete: Boolean = true) {
                        val current = transcript ?: return
                        try {
                            if (finalizeIncomplete) {
                                val started = SystemClock.elapsedRealtime()
                                merge(MoonshineNative.finishStream(handle, stream))
                                diagnostics.addInference(SystemClock.elapsedRealtime() - started)
                            }
                            val stable = current.snapshot(totalSamples)
                            // A rapid second seek discards only the unfinished hypothesis. Already
                            // finalized text remains valid, even when the user listened for <15s.
                            val safeEnd = if (!finalizeIncomplete) {
                                // No returned line does not prove silence: VAD may still buffer speech.
                                finalizedAsrBoundary(streamOwnedStartMs, endMs, stable)
                            } else endMs
                            savedCues = replaceAsrRangeCues(savedCues, stable, streamOwnedStartMs, safeEnd)
                            plan.record(streamOwnedStartMs, safeEnd)
                            safeResumeMs = safeEnd.coerceAtLeast(streamOwnedStartMs)
                        } finally {
                            val finishedStream = stream
                            stream = -1
                            transcript = null
                            lastStableCues = null
                            visibleCues = savedCues
                            MoonshineNative.freeStream(handle, finishedStream)
                        }
                    }

                    PodcastAudioDecoder.open(context, source, coroutineContext.job) { coroutineContext.ensureActive() }.use { decoder ->
                        durationHintMs = decoder.durationMs.takeIf { it > 0 } ?: totalDurationMs
                        diagnostics.record("audio_ready")
                        var preferredStart: Long? = resumePositionMs
                        while (true) {
                            coroutineContext.ensureActive()
                            val work = plan.next(preferredStart) ?: break
                            preferredStart = null
                            safeResumeMs = work.startMs
                            streamOwnedStartMs = work.startMs
                            totalSamples = work.startMs * (SAMPLE_RATE / 1_000)
                            var acceptedEndSample: Long? = null
                            var requestedSeek: AsrSeekTarget? = null
                            fun checkDecode() {
                                coroutineContext.ensureActive()
                                val target = requestedSeek ?: pollSeek()
                                if (target != null && (requestedSeek != null || shouldPrioritizeAsrSeek(
                                        target, work.startMs, totalSamples / (SAMPLE_RATE / 1_000), plan.processedRanges, savedCues,
                                    ))
                                ) {
                                    requestedSeek = target
                                    throw RecognitionReposition()
                                }
                            }
                            onPartial(snapshot(AsrRecognitionPhase.PREPARING))
                            val decoded = try {
                                decoder.decodeRange(
                                    startMs = (work.startMs - CONTEXT_AUDIO_MS).coerceAtLeast(0),
                                    endMs = work.endMs,
                                    checkCancelled = ::checkDecode,
                                    onSeek = { diagnostics.seek(it.requestedMs, it.actualMs, it.usedFallback) },
                                ) { chunk ->
                                    coroutineContext.ensureActive()
                                    checkDecode()
                                    val previousEnd = acceptedEndSample
                                    if (previousEnd != null && chunk.startSample != previousEnd) {
                                        check(chunk.startSample > previousEnd) { "Recognition media time moved backwards" }
                                        val previousMs = previousEnd / (SAMPLE_RATE / 1_000)
                                        finishCurrentStream(previousMs)
                                        plan.record(maxOf(work.startMs, previousMs), chunk.startMs)
                                        streamOwnedStartMs = maxOf(work.startMs, chunk.startMs)
                                        diagnostics.gap(previousMs, chunk.startMs)
                                    } else if (previousEnd == null && chunk.startMs > work.startMs) {
                                        plan.record(work.startMs, chunk.startMs)
                                        streamOwnedStartMs = chunk.startMs
                                    }
                                    if (stream < 0) {
                                        stream = MoonshineNative.createStream(handle)
                                        transcript = AsrTranscriptAccumulator(SAMPLE_RATE, chunk.startSample)
                                        safeResumeMs = streamOwnedStartMs
                                    }
                                    val inferenceStarted = SystemClock.elapsedRealtime()
                                    merge(MoonshineNative.acceptAudio(handle, stream, chunk.samples))
                                    diagnostics.addInference(SystemClock.elapsedRealtime() - inferenceStarted, chunk.samples.size)
                                    totalSamples = chunk.endSample
                                    acceptedEndSample = chunk.endSample
                                    coroutineContext.ensureActive()
                                    val current = checkNotNull(transcript)
                                    if (shouldRotateAsrStream(
                                            (totalSamples - current.streamStartSample) * 1_000 / SAMPLE_RATE,
                                            current.hasIncompleteLines,
                                        )
                                    ) {
                                        finishCurrentStream(chunk.endMs)
                                        streamOwnedStartMs = chunk.endMs
                                        diagnostics.record("stream_finished", chunk.endMs)
                                    }
                                    val partial = snapshot()
                                    onPartial(partial)
                                    diagnostics.progress(chunk.endMs, partial.cues.size)
                                    true
                                }
                            } catch (_: RecognitionReposition) {
                                null
                            }
                            coroutineContext.ensureActive()
                            val seek = requestedSeek
                            if (seek != null) {
                                val endMs = totalSamples / (SAMPLE_RATE / 1_000)
                                finishCurrentStream(endMs, endMs - streamOwnedStartMs >= MIN_INTERRUPTED_AUDIO_MS)
                                plan.defer(safeResumeMs)
                                val restartMs = plan.startAfterSeek(seek.positionMs, durationHintMs)
                                preferredStart = restartMs
                                diagnostics.record("reprioritized", restartMs, savedCues.size)
                            } else {
                                val completedRange = checkNotNull(decoded)
                                val endMs = completedRange.endSample / (SAMPLE_RATE / 1_000)
                                finishCurrentStream(endMs)
                                if (completedRange.reachedRangeEnd) plan.record(work.startMs, checkNotNull(work.endMs))
                                if (completedRange.reachedEndOfInput && !completedRange.reachedRangeEnd) {
                                    verifyAsrMediaEnd(endMs, processedRanges, retainedCues)
                                    plan.record(work.startMs, endMs)
                                    plan.reachedEnd(endMs)
                                    savedCues = savedCues.mapNotNull { cue ->
                                        val end = minOf(cue.endMs, endMs)
                                        if (end > cue.startMs) cue.copy(endMs = end) else null
                                    }
                                    visibleCues = savedCues
                                }
                                check(completedRange.reachedRangeEnd || completedRange.reachedEndOfInput) { "Recognition made no media progress" }
                                safeResumeMs = endMs
                            }
                            onPartial(snapshot())
                        }
                    }
                    coroutineContext.ensureActive()
                    val finished = snapshot()
                    diagnostics.record("recognition_finished", totalSamples / (SAMPLE_RATE / 1_000), finished.cues.size)
                    finished
                } finally {
                    if (stream >= 0) {
                        // Cancellation cannot finalize a native hypothesis, but completed sentences
                        // must still advance the durable cursor before the stream is released.
                        runCatching { publishCheckpoint?.invoke() }
                        runCatching { MoonshineNative.freeStream(handle, stream) }
                    }
                    MoonshineNative.close(handle)
                    diagnostics.record("model_released", totalSamples * 1_000 / SAMPLE_RATE)
                }
            }
            Result.success(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(
                if (error is AsrModelPreparationException || error is AsrRecordingChangedException) error
                else IllegalStateException(context.getString(R.string.podcast_subtitle_asr_failed), error),
            )
        } catch (error: OutOfMemoryError) {
            // Decoder, PCM arrays and the native recognizer have unwound before retrying.
            Result.failure(IllegalStateException(context.getString(R.string.podcast_subtitle_asr_failed), error))
        } catch (error: LinkageError) {
            Result.failure(IllegalStateException(context.getString(R.string.podcast_subtitle_asr_failed), error))
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CONTEXT_AUDIO_MS = 8_000L
        private const val MIN_INTERRUPTED_AUDIO_MS = 15_000L
        fun checkpointIdentity(language: AsrLanguage = AsrLanguage.ENGLISH, variant: AsrModelVariant = language.modelVariant): String = language.model(variant).checkpointIdentity
        fun approximateModelDownloadBytes(language: AsrLanguage = AsrLanguage.ENGLISH, variant: AsrModelVariant = language.modelVariant): Long = MoonshineModelFiles.artifacts(language.model(variant)).sumOf { it.bytes }
    }

    private class RecognitionReposition : RuntimeException(null, null, false, false)
}

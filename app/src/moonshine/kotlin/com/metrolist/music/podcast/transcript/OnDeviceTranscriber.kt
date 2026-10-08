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
import kotlinx.coroutines.withContext

/** Uses the streaming recognizer without retaining decoded audio for the entire episode. */
@Singleton
class OnDeviceTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modelStore = AsrModelStore(context, MoonshineModelFiles.DIRECTORY, MoonshineModelFiles.artifacts)

    fun isModelReady(): Boolean = modelStore.isReady()
    fun modelStorageBytes(): Long = modelStore.storageBytes()
    suspend fun deleteModels(): Boolean = modelStore.delete()
    suspend fun invalidateInvalidModelFiles(): Boolean = modelStore.invalidateInvalidFiles()
    suspend fun downloadModels(onProgress: (Long, Long) -> Unit): Result<Unit> = modelStore.download(onProgress)
    suspend fun importModels(treeUri: Uri, onProgress: (Long, Long) -> Unit): Result<Unit> =
        modelStore.importFromTree(treeUri, onProgress)

    suspend fun removeRetiredModelFiles() = withContext(Dispatchers.IO) {
        removeRetiredAsrModelFiles(context.filesDir)
    }

    suspend fun transcribe(
        source: String,
        totalDurationMs: Long,
        resumePositionMs: Long = 0L,
        retainedCues: List<SubtitleCue> = emptyList(),
        onPartial: (cues: List<SubtitleCue>, progressPercent: Int, resumePositionMs: Long) -> Unit,
    ): Result<Pair<List<SubtitleCue>, String?>> = withContext(Dispatchers.Default) {
        try {
            val result = modelStore.withModel { directory ->
                val diagnostics = AsrDiagnostics()
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()
                val resumeCursor = AsrResumeCursor(resumePositionMs, SAMPLE_RATE)
                val transcript = AsrTranscriptAccumulator(SAMPLE_RATE, resumeCursor.resumeSample, retainedCues)
                var totalSamples = resumeCursor.resumeSample
                var stream = -1
                val handle = MoonshineNative.create(directory.absolutePath)

                try {
                    diagnostics.record("model_loaded")
                    fun merge(lines: Array<MoonshineLine>) {
                        lines.forEach { transcript.update(it.id, it.text, it.startTimeSeconds, it.durationSeconds, it.complete) }
                    }

                    fun finishCurrentStream() {
                        merge(MoonshineNative.finishStream(handle, stream))
                        transcript.finishStream(totalSamples)
                        MoonshineNative.freeStream(handle, stream)
                        stream = -1
                    }

                    stream = MoonshineNative.createStream(handle)
                    PodcastAudioDecoder.decodeToPcmChunks(source, SAMPLE_RATE * 2, { coroutineContext.ensureActive() }) { decoded, _ ->
                        coroutineContext.ensureActive()
                        val samples = resumeCursor.remaining(decoded)
                        if (samples.isNotEmpty()) {
                            val inferenceStarted = SystemClock.elapsedRealtime()
                            merge(MoonshineNative.acceptAudio(handle, stream, samples))
                            diagnostics.addInference(SystemClock.elapsedRealtime() - inferenceStarted)
                            totalSamples += samples.size
                            coroutineContext.ensureActive()

                            // Only a finalized native stream is a safe restart boundary. Keep
                            // the unfinished stream in the visible snapshot, but reprocess it on resume.
                            if (shouldRotateAsrStream((totalSamples - transcript.streamStartSample) * 1_000 / SAMPLE_RATE, transcript.hasIncompleteLines)) {
                                finishCurrentStream()
                                diagnostics.record("stream_finished", totalSamples * 1_000 / SAMPLE_RATE)
                                stream = MoonshineNative.createStream(handle)
                            }
                            val cues = transcript.snapshot(totalSamples)
                            val progress = if (totalDurationMs > 0) {
                                (totalSamples * 100_000L / SAMPLE_RATE / totalDurationMs).toInt().coerceIn(0, 99)
                            } else 0
                            onPartial(cues, progress, transcript.streamStartSample * 1_000 / SAMPLE_RATE)
                            diagnostics.progress(totalSamples * 1_000 / SAMPLE_RATE, cues.size)
                        }
                    }
                    coroutineContext.ensureActive()
                    resumeCursor.verifyEndOfInput()
                    finishCurrentStream()
                    val cues = transcript.snapshot(totalSamples)
                    onPartial(cues, 99, transcript.streamStartSample * 1_000 / SAMPLE_RATE)
                    diagnostics.record("recognition_finished", totalSamples * 1_000 / SAMPLE_RATE, cues.size)
                    cues to "en"
                } finally {
                    if (stream >= 0) runCatching { MoonshineNative.freeStream(handle, stream) }
                    MoonshineNative.close(handle)
                    diagnostics.record("model_released", totalSamples * 1_000 / SAMPLE_RATE)
                }
            }
            Result.success(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(if (error is AsrModelPreparationException) error else IllegalStateException(context.getString(R.string.podcast_subtitle_asr_failed), error))
        } catch (error: LinkageError) {
            Result.failure(IllegalStateException(context.getString(R.string.podcast_subtitle_asr_failed), error))
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        fun checkpointIdentity(): String = "moonshine-small-streaming-en-quantized_26_08_21-v1"
        fun approximateModelDownloadBytes(): Long = MoonshineModelFiles.artifacts.sumOf { it.bytes }
    }
}

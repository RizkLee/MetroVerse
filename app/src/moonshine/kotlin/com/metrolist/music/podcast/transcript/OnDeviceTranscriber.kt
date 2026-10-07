/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import android.net.Uri
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

    suspend fun transcribe(
        source: String,
        totalDurationMs: Long,
        onPartial: (cues: List<SubtitleCue>, progressPercent: Int) -> Unit,
    ): Result<Pair<List<SubtitleCue>, String?>> = withContext(Dispatchers.Default) {
        try {
            val result = modelStore.withModel { directory ->
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()
                val handle = MoonshineNative.create(directory.absolutePath)
                var stream = -1
                val transcript = AsrTranscriptAccumulator(SAMPLE_RATE)
                var totalSamples = 0L

                fun merge(lines: Array<MoonshineLine>) {
                    lines.forEach { transcript.update(it.id, it.text, it.startTimeSeconds, it.durationSeconds, it.complete) }
                }

                fun finishCurrentStream() {
                    merge(MoonshineNative.finishStream(handle, stream))
                    transcript.finishStream(totalSamples)
                    MoonshineNative.freeStream(handle, stream)
                    stream = -1
                }

                try {
                    stream = MoonshineNative.createStream(handle)
                    PodcastAudioDecoder.decodeToPcmChunks(source, SAMPLE_RATE * 2, { coroutineContext.ensureActive() }) { samples, _ ->
                        coroutineContext.ensureActive()
                        merge(MoonshineNative.acceptAudio(handle, stream, samples))
                        totalSamples += samples.size
                        coroutineContext.ensureActive()
                        val progress = if (totalDurationMs > 0) {
                            (totalSamples * 100_000L / SAMPLE_RATE / totalDurationMs).toInt().coerceIn(0, 99)
                        } else 0
                        onPartial(transcript.snapshot(totalSamples), progress)

                        // Prefer a speech boundary after five minutes; flush by six minutes to bound
                        // native line metadata while preserving every input sample and the episode clock.
                        if (shouldRotateAsrStream((totalSamples - transcript.streamStartSample) * 1_000 / SAMPLE_RATE, transcript.hasIncompleteLines)) {
                            finishCurrentStream()
                            stream = MoonshineNative.createStream(handle)
                        }
                    }
                    coroutineContext.ensureActive()
                    finishCurrentStream()
                    val cues = transcript.snapshot(totalSamples)
                    onPartial(cues, 99)
                    cues to "en"
                } finally {
                    if (stream >= 0) runCatching { MoonshineNative.freeStream(handle, stream) }
                    MoonshineNative.close(handle)
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
        fun approximateModelDownloadBytes(): Long = MoonshineModelFiles.artifacts.sumOf { it.bytes }
    }
}

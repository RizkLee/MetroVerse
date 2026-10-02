/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.metrolist.music.podcast.PODCAST_USER_AGENT
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device podcast transcription with sherpa-onnx (SenseVoice-Small int8 + Silero VAD).
 *
 * Audio is decoded streaming in chunks; VAD cuts speech segments which are recognized
 * immediately, so memory stays bounded regardless of episode length and partial results
 * can be surfaced while the job runs.
 */
@Singleton
class OnDeviceTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val asrDir: File
        get() = File(context.filesDir, "podcast/asr")

    private val modelFile: File get() = File(asrDir, MODEL_DIR).resolve(MODEL_FILE)
    private val tokensFile: File get() = File(asrDir, MODEL_DIR).resolve(TOKENS_FILE)
    private val vadFile: File get() = File(asrDir, VAD_FILE)
    @Volatile
    private var validatedModelKey: String? = null

    /**
     * sherpa-onnx loads models natively and calls exit(-1) inside C++ when a file cannot be
     * parsed, which kills the process instantly. Guard every load behind strict file checks so
     * a corrupt or truncated download can never reach the native layer.
     */
    fun isModelReady(): Boolean = hasValidModelFiles()

    /** Returns the total storage used by the optional on-device recognition model. */
    fun modelStorageBytes(): Long =
        asrDir.takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L

    /** Removes the optional ASR model without touching generated episode transcripts. */
    fun deleteModels(): Boolean {
        validatedModelKey = null
        return asrDir.exists() && asrDir.deleteRecursively()
    }

    private fun hasValidModelFiles(): Boolean {
        val key = listOf(modelFile, tokensFile, vadFile)
            .joinToString(separator = ":") { "${it.length()}:${it.lastModified()}" }
        if (validatedModelKey == key) return true
        if (modelFile.length() != EXPECTED_MODEL_BYTES ||
            tokensFile.length() < MIN_TOKENS_BYTES ||
            vadFile.length() < MIN_VAD_BYTES
        ) {
            validatedModelKey = null
            return false
        }
        val valid = sha256(modelFile) == EXPECTED_MODEL_SHA256
        validatedModelKey = key.takeIf { valid }
        return valid
    }

    /** Deletes invalid model artifacts so the download flow starts over cleanly. */
    fun invalidateInvalidModelFiles(): Boolean {
        val invalid = !hasValidModelFiles()
        if (invalid) {
            validatedModelKey = null
            modelFile.delete()
            tokensFile.delete()
            vadFile.delete()
        }
        return invalid
    }

    /** Downloads the SenseVoice int8 model and the Silero VAD model with progress reporting. */
    suspend fun downloadModels(onProgress: (bytes: Long, totalBytes: Long) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val total = EXPECTED_MODEL_BYTES + EXPECTED_TOKENS_BYTES + EXPECTED_VAD_BYTES
                var completed = 0L
                fun report(bytes: Long) = onProgress((completed + bytes).coerceAtMost(total), total)

                downloadToFile(
                    MODEL_URLS,
                    modelFile,
                    MIN_MODEL_BYTES,
                    { bytes, _ -> report(bytes) },
                    expectedBytes = EXPECTED_MODEL_BYTES,
                    expectedSha256 = EXPECTED_MODEL_SHA256,
                )
                completed += EXPECTED_MODEL_BYTES
                report(0L)
                downloadToFile(
                    TOKENS_URLS,
                    tokensFile,
                    MIN_TOKENS_BYTES,
                    { bytes, _ -> report(bytes) },
                )
                completed += EXPECTED_TOKENS_BYTES
                report(0L)
                downloadToFile(
                    VAD_URLS,
                    vadFile,
                    MIN_VAD_BYTES,
                    { bytes, _ -> report(bytes) },
                )
                onProgress(total, total)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                validatedModelKey = null
                modelFile.delete()
                tokensFile.delete()
                vadFile.delete()
            }
        }

    /**
     * Transcribes the audio at [source] and reports cumulative cues as they are produced.
     * [languageMode] is one of "auto", "zh" or "en".
     */
    suspend fun transcribe(
        source: String,
        languageMode: String,
        totalDurationMs: Long,
        onPartial: (cues: List<SubtitleCue>, progressPercent: Int) -> Unit,
    ): Result<Pair<List<SubtitleCue>, String?>> = withContext(Dispatchers.Default) {
        runCatching {
            require(isModelReady()) { "ASR model is not downloaded" }

            val vad = com.k2fsa.sherpa.onnx.Vad(
                config = com.k2fsa.sherpa.onnx.VadModelConfig(
                    sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )

            val languageArg = if (languageMode == "zh" || languageMode == "en") languageMode else ""
            val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(
                assetManager = null,
                config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                    featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80,
                    ),
                    modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                        senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                            model = modelFile.absolutePath,
                            language = languageArg,
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokensFile.absolutePath,
                        numThreads = 2,
                        debug = false,
                    ),
                    decodingMethod = "greedy_search",
                ),
            )

            try {
                val totalSamplesEstimate =
                    if (totalDurationMs > 0) totalDurationMs * SAMPLE_RATE / 1000 else 0L
                val coroutineContext = currentCoroutineContext()
                val cues = mutableListOf<SubtitleCue>()
                var detectedLanguage: String? = null
                var samplesFed = 0L

                fun drainSegments(finalFlush: Boolean) {
                    coroutineContext.ensureActive()
                    if (finalFlush) vad.flush()
                    while (!vad.empty()) {
                        coroutineContext.ensureActive()
                        val segment = vad.front()
                        vad.pop()
                        val startMs = segment.start * 1000L / SAMPLE_RATE
                        val endMs = startMs + segment.samples.size * 1000L / SAMPLE_RATE
                        val stream = recognizer.createStream()
                        stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                        recognizer.decode(stream)
                        val result = recognizer.getResult(stream)
                        stream.release()

                        val text = result.text.trim()
                        if (text.isNotEmpty()) {
                            if (detectedLanguage == null) {
                                detectedLanguage = PodcastAsrSupport.normalizeLanguageTag(result.lang)
                            }
                            cues.addAll(
                                PodcastAsrSupport.splitLongCue(SubtitleCue(startMs, endMs, text)),
                            )
                        }
                        onPartial(cues.toList(), progressPercent(samplesFed, totalSamplesEstimate))
                    }
                }

                AudioDecoder.decodeToPcmChunks(
                    source = source,
                    chunkSamples = SAMPLE_RATE * 30,
                ) { chunk, _ ->
                    coroutineContext.ensureActive()
                    vad.acceptWaveform(chunk)
                    samplesFed += chunk.size
                    drainSegments(finalFlush = false)
                }
                drainSegments(finalFlush = true)

                // Timestamps: segment starts are exact; assign each cue an end at the next
                // cue's start (or a nominal display duration for the trailing one).
                val normalized = cues.mapIndexed { index, cue ->
                    val end = cues.getOrNull(index + 1)?.startMs
                        ?: (cue.startMs + DEFAULT_ASR_CUE_END_MS)
                    SubtitleCue(cue.startMs, maxOf(end, cue.startMs + 1000), cue.text)
                }
                normalized to detectedLanguage
            } finally {
                vad.release()
                recognizer.release()
            }
        }
    }

    private fun progressPercent(samplesFed: Long, totalSamples: Long): Int =
        if (totalSamples <= 0) 0 else ((samplesFed * 100) / totalSamples).toInt().coerceIn(0, 99)

    private suspend fun downloadToFile(
        urls: List<String>,
        target: File,
        minBytes: Long,
        onProgress: (bytes: Long, totalBytes: Long) -> Unit,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
    ) {
        if (target.exists() && target.length() >= minBytes) {
            val validSize = expectedBytes == null || target.length() == expectedBytes
            val validHash = expectedSha256 == null || sha256(target) == expectedSha256
            if (validSize && validHash) return
            target.delete()
        }
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".part")

        var lastError: Throwable? = null
        for (url in urls) {
            try {
                partial.delete()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", PODCAST_USER_AGENT)
                    .build()
                val response = okHttpClient.newCall(request).execute()
                response.use { httpResponse ->
                    check(httpResponse.isSuccessful) { "Model download failed (${httpResponse.code}): $url" }
                    val body = httpResponse.body
                    val total = body.contentLength()
                    var written = 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (written - lastReport > PROGRESS_STEP_BYTES) {
                                    lastReport = written
                                    onProgress(written, total)
                                }
                            }
                            onProgress(written, total)
                        }
                    }
                    // Reject truncated or proxy-blocked responses (e.g. HTML error pages
                    // served with HTTP 200) before they can reach the native loader.
                    check(written >= minBytes) { "Model download too small ($written bytes): $url" }
                    check(total <= 0L || written == total) {
                        "Model download incomplete ($written of $total bytes): $url"
                    }
                    check(expectedBytes == null || written == expectedBytes) {
                        "Model download has unexpected size ($written bytes): $url"
                    }
                    check(expectedSha256 == null || sha256(partial) == expectedSha256) {
                        "Model download checksum mismatch: $url"
                    }
                    check(partial.renameTo(target)) { "Failed to finalize model file: $url" }
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Model download failed: $urls")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Streams an audio URL into 16 kHz mono float chunks via the platform decoder. */
    object AudioDecoder {

        fun decodeToPcmChunks(
            source: String,
            chunkSamples: Int,
            onChunk: (FloatArray, Int) -> Unit,
        ) {
            val extractor = MediaExtractor()
            val headers = mapOf("User-Agent" to PODCAST_USER_AGENT)
            try {
                extractor.setDataSource(source, headers)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (index in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(index)
                    val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        trackIndex = index
                        format = trackFormat
                        break
                    }
                }
                check(trackIndex >= 0 && format != null) { "No audio track in $source" }
                extractor.selectTrack(trackIndex)

                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))

                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val carry = FloatArray(chunkSamples)
                var carrySize = 0

                fun feed(resampled: FloatArray) {
                    var offset = 0
                    while (offset < resampled.size) {
                        val toCopy = minOf(chunkSamples - carrySize, resampled.size - offset)
                        System.arraycopy(resampled, offset, carry, carrySize, toCopy)
                        carrySize += toCopy
                        offset += toCopy
                        if (carrySize == chunkSamples) {
                            onChunk(carry.copyOf(), SAMPLE_RATE)
                            carrySize = 0
                        }
                    }
                }

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            val size = bufferInfo.size
                            if (size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + size)
                            val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val shorts = ShortArray(shortBuffer.remaining())
                            shortBuffer.get(shorts)
                            feed(
                                PodcastAsrSupport.resampleToMono16k(shorts, channelCount, sampleRate),
                            )
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                if (carrySize > 0) onChunk(carry.copyOf(carrySize), SAMPLE_RATE)
                codec.stop()
                codec.release()
            } finally {
                extractor.release()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "sense-voice"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val VAD_FILE = "silero_vad.onnx"
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024L
        private const val DEFAULT_ASR_CUE_END_MS = 5_000L

        // SenseVoice int8 2025-09-09. The SHA-256 is the public Hugging Face LFS checksum.
        const val EXPECTED_MODEL_BYTES = 237_115_547L
        const val EXPECTED_MODEL_SHA256 = "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d"

        // Native model loading cannot report failures gracefully, so anything below these
        // sizes is treated as a corrupt download and re-fetched.
        const val EXPECTED_TOKENS_BYTES = 320_000L
        const val EXPECTED_VAD_BYTES = 2_500_000L
        const val MIN_MODEL_BYTES = 220L * 1024 * 1024
        const val MIN_TOKENS_BYTES = 128L * 1024
        const val MIN_VAD_BYTES = 512L * 1024

        /** Primary sources first, then mirrors reachable from mainland China. */
        val MODEL_URLS = listOf(
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/model.int8.onnx",
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/model.int8.onnx",
        )
        val TOKENS_URLS = listOf(
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/tokens.txt",
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/tokens.txt",
        )
        val VAD_URLS = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-silero-vad/resolve/main/silero_vad.onnx",
        )

        fun approximateModelDownloadBytes(): Long = EXPECTED_MODEL_BYTES + 320_000L + 2_500_000L
    }
}

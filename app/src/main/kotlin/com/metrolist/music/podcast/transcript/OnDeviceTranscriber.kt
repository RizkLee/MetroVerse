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
import kotlin.math.sqrt

/**
 * On-device podcast transcription with sherpa-onnx's English streaming Zipformer2 model.
 *
 * Audio is decoded in bounded chunks and split by a Kotlin-side RMS segmenter before it reaches
 * the recognizer, so no native VAD queue can grow with the episode length.
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

    private val modelDir: File
        get() = File(asrDir, MODEL_DIR)

    private val modelFile: File get() = modelDir.resolve(ENCODER_FILE)
    private val decoderFile: File get() = modelDir.resolve(DECODER_FILE)
    private val joinerFile: File get() = modelDir.resolve(JOINER_FILE)
    private val tokensFile: File get() = modelDir.resolve(TOKENS_FILE)
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
        val artifacts = modelArtifacts()
        val key = artifacts.joinToString(separator = ":") {
            "${it.file.length()}:${it.file.lastModified()}"
        }
        if (validatedModelKey == key) return true
        val valid = artifacts.all { artifact ->
            artifact.file.length() == artifact.expectedBytes &&
                sha256(artifact.file) == artifact.expectedSha256
        }
        validatedModelKey = key.takeIf { valid }
        return valid
    }

    /** Deletes invalid model artifacts so the download flow starts over cleanly. */
    fun invalidateInvalidModelFiles(): Boolean {
        val invalid = !hasValidModelFiles()
        if (invalid) {
            validatedModelKey = null
            modelArtifacts().forEach { it.file.delete() }
            modelDir.delete()
        }
        return invalid
    }

    /** Downloads the English streaming Zipformer2 model with cumulative progress. */
    suspend fun downloadModels(onProgress: (bytes: Long, totalBytes: Long) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val artifacts = modelArtifacts()
                val total = artifacts.sumOf { it.expectedBytes }
                var completed = 0L
                fun report(bytes: Long) = onProgress((completed + bytes).coerceAtMost(total), total)

                artifacts.forEach { artifact ->
                    downloadToFile(
                        urls = artifact.urls,
                        target = artifact.file,
                        minBytes = artifact.expectedBytes,
                        onProgress = { bytes, _ -> report(bytes) },
                        expectedBytes = artifact.expectedBytes,
                        expectedSha256 = artifact.expectedSha256,
                    )
                    completed += artifact.expectedBytes
                    report(0L)
                }
                onProgress(total, total)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                validatedModelKey = null
                modelArtifacts().forEach { it.file.delete() }
                modelDir.delete()
            }
        }

    private data class ModelArtifact(
        val file: File,
        val urls: List<String>,
        val expectedBytes: Long,
        val expectedSha256: String,
    )

    private fun modelArtifacts(): List<ModelArtifact> = listOf(
        ModelArtifact(modelFile, ENCODER_URLS, ENCODER_BYTES, ENCODER_SHA256),
        ModelArtifact(decoderFile, DECODER_URLS, DECODER_BYTES, DECODER_SHA256),
        ModelArtifact(joinerFile, JOINER_URLS, JOINER_BYTES, JOINER_SHA256),
        ModelArtifact(tokensFile, TOKENS_URLS, TOKENS_BYTES, TOKENS_SHA256),
    )

    /** Transcribes the audio at [source] and reports cumulative cues as they are produced. */
    suspend fun transcribe(
        source: String,
        totalDurationMs: Long,
        onPartial: (cues: List<SubtitleCue>, progressPercent: Int) -> Unit,
    ): Result<Pair<List<SubtitleCue>, String?>> = withContext(Dispatchers.Default) {
        runCatching {
            require(isModelReady()) { "ASR model is not downloaded" }

            val recognizer = com.k2fsa.sherpa.onnx.OnlineRecognizer(
                assetManager = null,
                config = com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(
                    featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80,
                    ),
                    modelConfig = com.k2fsa.sherpa.onnx.OnlineModelConfig(
                        transducer = com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig(
                            encoder = modelFile.absolutePath,
                            decoder = decoderFile.absolutePath,
                            joiner = joinerFile.absolutePath,
                        ),
                        tokens = tokensFile.absolutePath,
                        numThreads = 1,
                        provider = "cpu",
                        modelType = "zipformer2",
                        debug = false,
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search",
                ),
            )

            try {
                val totalSamplesEstimate =
                    if (totalDurationMs > 0) totalDurationMs * SAMPLE_RATE / 1000 else 0L
                val coroutineContext = currentCoroutineContext()
                val cues = mutableListOf<SubtitleCue>()
                var samplesFed = 0L

                fun recognize(startSample: Long, samples: FloatArray) {
                    coroutineContext.ensureActive()
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        stream.inputFinished()
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text.trim()
                        if (text.isNotEmpty()) {
                            val startMs = startSample * 1000L / SAMPLE_RATE
                            val endMs = (startSample + samples.size) * 1000L / SAMPLE_RATE
                            cues.addAll(
                                PodcastAsrSupport.splitLongCue(
                                    SubtitleCue(startMs, maxOf(endMs, startMs + 1_000L), text),
                                ),
                            )
                        }
                    } finally {
                        stream.release()
                    }
                    onPartial(cues.toList(), progressPercent(samplesFed, totalSamplesEstimate))
                }

                val segmenter = RmsSpeechSegmenter { startSample, samples ->
                    recognize(startSample, samples)
                }
                AudioDecoder.decodeToPcmChunks(
                    source = source,
                    chunkSamples = SAMPLE_RATE * 2,
                ) { chunk, _ ->
                    coroutineContext.ensureActive()
                    samplesFed += chunk.size
                    segmenter.accept(chunk)
                    onPartial(cues.toList(), progressPercent(samplesFed, totalSamplesEstimate))
                }
                segmenter.flush()
                onPartial(cues.toList(), 99)
                cues to "en"
            } finally {
                recognizer.release()
            }
        }
    }

    private class RmsSpeechSegmenter(
        private val onSegment: (startSample: Long, samples: FloatArray) -> Unit,
    ) {
        private val pending = FloatArray(RMS_FRAME_SAMPLES)
        private val speech = FloatArray(MAX_SEGMENT_SAMPLES + RMS_FRAME_SAMPLES)
        private var pendingSize = 0
        private var frameStartSample = 0L
        private var speechStartSample = 0L
        private var speechSize = 0
        private var silenceFrames = 0
        private var inSpeech = false

        fun accept(samples: FloatArray) {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(RMS_FRAME_SAMPLES - pendingSize, samples.size - offset)
                System.arraycopy(samples, offset, pending, pendingSize, count)
                pendingSize += count
                offset += count
                if (pendingSize == RMS_FRAME_SAMPLES) {
                    processFrame(pending)
                    pendingSize = 0
                    frameStartSample += RMS_FRAME_SAMPLES
                }
            }
        }

        fun flush() {
            if (pendingSize > 0) {
                pending.fill(0f, pendingSize, pending.size)
                processFrame(pending)
                pendingSize = 0
                frameStartSample += RMS_FRAME_SAMPLES
            }
            finishSegment()
        }

        private fun processFrame(frame: FloatArray) {
            val rms = sqrt(frame.sumOf { (it * it).toDouble() } / frame.size).toFloat()
            if (rms >= RMS_SPEECH_THRESHOLD) {
                if (!inSpeech) {
                    inSpeech = true
                    speechStartSample = frameStartSample
                    speechSize = 0
                }
                silenceFrames = 0
                append(frame)
                if (speechSize >= MAX_SEGMENT_SAMPLES) finishSegment()
            } else if (inSpeech) {
                append(frame)
                silenceFrames++
                if (silenceFrames >= RMS_SILENCE_FRAMES) finishSegment()
            }
        }

        private fun append(frame: FloatArray) {
            val count = minOf(frame.size, speech.size - speechSize)
            System.arraycopy(frame, 0, speech, speechSize, count)
            speechSize += count
        }

        private fun finishSegment() {
            if (!inSpeech) return
            if (speechSize >= RMS_MIN_SPEECH_SAMPLES) {
                onSegment(speechStartSample, speech.copyOf(speechSize))
            }
            inSpeech = false
            speechSize = 0
            silenceFrames = 0
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
                try {
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
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "zipformer-en"
        private const val ENCODER_FILE = "encoder.int8.onnx"
        private const val DECODER_FILE = "decoder.onnx"
        private const val JOINER_FILE = "joiner.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024L

        // sherpa-onnx-streaming-zipformer-en-2023-06-26, Apache-2.0.
        // The hashes are the public Hugging Face LFS SHA-256 values.
        private const val ENCODER_BYTES = 71_083_163L
        private const val ENCODER_SHA256 = "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"
        private const val DECODER_BYTES = 2_092_621L
        private const val DECODER_SHA256 = "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef"
        private const val JOINER_BYTES = 259_335L
        private const val JOINER_SHA256 = "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"
        private const val TOKENS_BYTES = 5_048L
        private const val TOKENS_SHA256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"

        // The 2023-06-26 model uses the chunk-16/left-128 streaming configuration.
        private val ENCODER_URLS = modelUrls("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx")
        private val DECODER_URLS = modelUrls("decoder-epoch-99-avg-1-chunk-16-left-128.onnx")
        private val JOINER_URLS = modelUrls("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx")
        private val TOKENS_URLS = modelUrls(TOKENS_FILE)

        private fun modelUrls(file: String): List<String> = listOf(
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/$file",
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/$file",
        )

        private const val RMS_FRAME_SAMPLES = 1_600 // 100 ms at 16 kHz.
        private const val RMS_SPEECH_THRESHOLD = 0.012f
        private const val RMS_SILENCE_FRAMES = 5 // 500 ms.
        private const val RMS_MIN_SPEECH_SAMPLES = 4_000 // 250 ms.
        private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 20

        fun approximateModelDownloadBytes(): Long =
            ENCODER_BYTES + DECODER_BYTES + JOINER_BYTES + TOKENS_BYTES
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.metrolist.music.podcast.PODCAST_USER_AGENT
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Streams bounded ranges from one opened audio source without retaining an episode's PCM. */
internal object PodcastAudioDecoder {
    data class SeekInfo(val requestedMs: Long, val actualMs: Long, val usedFallback: Boolean)

    data class DecodeResult(
        // Reaching a requested boundary also covers timestamp gaps without inventing PCM.
        val endSample: Long,
        val reachedRangeEnd: Boolean,
        val reachedEndOfInput: Boolean,
        val stoppedEarly: Boolean,
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun open(context: Context, source: String, ownerJob: Job? = null, checkCancelled: () -> Unit = {}): Session {
        checkCancelled()
        // A child with no coroutine completes immediately on parent cancellation, so its
        // handler can abort socket reads while the recognition coroutine is still unwinding.
        val connectionJob = ownerJob?.let { Job(it) }
        val activeCall = AtomicReference<Call?>(null)
        connectionJob?.invokeOnCompletion {
            if (connectionJob.isCancelled) activeCall.get()?.cancel()
        }
        val callFactory = Call.Factory { request ->
            checkCancelled()
            httpClient.newCall(request).also { call ->
                activeCall.set(call)
                if (connectionJob?.isCancelled == true) call.cancel()
            }
        }
        val dataSource = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(callFactory).setUserAgent(PODCAST_USER_AGENT),
        ).createDataSource()
        val extractor = PodcastAudioExtractor(dataSource, DataSpec(source.toUri()), checkCancelled)
        return try {
            extractor.prepare()
            Session(extractor, MediaFormatUtil.createMediaFormatFromFormat(extractor.format)) { connectionJob?.complete() }
        } catch (error: Throwable) {
            connectionJob?.complete()
            extractor.close()
            throw error
        }
    }

    /** Used serially by one recognition task; seeking retains the container's seek map. */
    class Session internal constructor(
        private val extractor: PodcastAudioExtractor,
        private val format: MediaFormat,
        private val onClosed: () -> Unit = {},
    ) : Closeable {
        // Track metadata is a hint, never the cutoff for decoding the episode's tail.
        val durationMs: Long get() = extractor.durationMs

        private var closed = false
        private var decoding = false

        fun decodeRange(
            startMs: Long,
            endMs: Long? = null,
            chunkSamples: Int = ASR_PCM_SAMPLE_RATE * 2,
            checkCancelled: () -> Unit = {},
            onSeek: (SeekInfo) -> Unit = {},
            onChunk: (TimedPcmChunk) -> Boolean,
        ): DecodeResult {
            require(startMs in 0..Long.MAX_VALUE / 1_000)
            require(endMs == null || endMs > startMs && endMs <= Long.MAX_VALUE / 1_000)
            require(chunkSamples > 0)
            check(!closed && !decoding) { "Audio decoder session is unavailable" }
            decoding = true
            try {
                checkCancelled()
                val seekInfo = seek(startMs, checkCancelled)
                onSeek(seekInfo)
                checkCancelled()
                return if (extractor.format.sampleMimeType == MimeTypes.AUDIO_RAW) {
                    decodeRawRange(startMs, endMs, chunkSamples, checkCancelled, onChunk)
                } else {
                    decodePositionedRange(startMs, endMs, chunkSamples, seekInfo.usedFallback, checkCancelled, onChunk)
                }
            } finally {
                decoding = false
            }
        }

        private fun seek(startMs: Long, checkCancelled: () -> Unit): SeekInfo {
            val position = extractor.seek(startMs, checkCancelled)
            return SeekInfo(position.requestedMs, position.actualMs, position.sequential)
        }

        private fun decodeRawRange(
            startMs: Long,
            endMs: Long?,
            chunkSamples: Int,
            checkCancelled: () -> Unit,
            onChunk: (TimedPcmChunk) -> Boolean,
        ): DecodeResult {
            val audio = extractor.format
            val rate = audio.sampleRate
            val channels = audio.channelCount
            val encoding = audio.pcmEncoding
            check(rate > 0 && channels > 0)
            val bytesPerSample = when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> error("Unsupported podcast PCM encoding")
            }
            val clock = PcmTimestampTracker()
            var resampler: StreamingPcmResampler? = null
            var segmentStart = 0L
            val samplesPerMs = ASR_PCM_SAMPLE_RATE / 1_000
            val chunks = TimedPcmChunkBuffer(startMs * samplesPerMs, endMs?.times(samplesPerMs), chunkSamples) {
                checkCancelled()
                onChunk(it)
            }
            fun finishSegment() {
                resampler?.let { current ->
                    val firstSample = segmentStart + current.outputSampleCount
                    chunks.append(current.finish(), firstSample)
                }
                chunks.finishSegment()
            }
            var endOfInput = false
            while (chunks.isActive) {
                checkCancelled()
                val sample = extractor.nextSample()
                if (sample == null) {
                    endOfInput = true
                    break
                }
                if (sample.data.isEmpty()) continue
                check(sample.data.size % (bytesPerSample * channels) == 0)
                val buffer = ByteBuffer.wrap(sample.data).order(ByteOrder.LITTLE_ENDIAN)
                val pcm = FloatArray(sample.data.size / bytesPerSample) {
                    when (encoding) {
                        C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                        C.ENCODING_PCM_16BIT -> buffer.short / 32768f
                        C.ENCODING_PCM_24BIT -> {
                            val value = (buffer.get().toInt() and 0xff) or
                                ((buffer.get().toInt() and 0xff) shl 8) or (buffer.get().toInt() shl 16)
                            value / 8388608f
                        }
                        C.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                        else -> buffer.float
                    }
                }
                val position = clock.locate(sample.timeUs, pcm.size / channels, rate, channels)
                if (position.startsNewSegment) {
                    finishSegment()
                    if (!chunks.isActive) break
                    resampler = StreamingPcmResampler(rate, channels)
                    segmentStart = position.segmentStartSample
                }
                val skip = position.skipFrames * channels
                if (skip < pcm.size) {
                    val current = checkNotNull(resampler)
                    val first = segmentStart + current.outputSampleCount
                    chunks.append(current.process(if (skip == 0) pcm else pcm.copyOfRange(skip, pcm.size)), first)
                }
            }
            if (chunks.isActive) finishSegment()
            checkCancelled()
            return DecodeResult(
                endSample = if (chunks.reachedRangeEnd && endMs != null) {
                    endMs * samplesPerMs
                } else chunks.emittedEndSample ?: clock.endSample,
                reachedRangeEnd = chunks.reachedRangeEnd,
                reachedEndOfInput = endOfInput,
                stoppedEarly = chunks.stoppedEarly,
            )
        }

        private fun decodePositionedRange(
            startMs: Long,
            endMs: Long?,
            chunkSamples: Int,
            sequentialFallback: Boolean,
            checkCancelled: () -> Unit,
            onChunk: (TimedPcmChunk) -> Boolean,
        ): DecodeResult {
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingPcmResampler? = null
            var segmentStartSample = 0L
            val clock = PcmTimestampTracker()
            val chunks = TimedPcmChunkBuffer(
                rangeStartSample = startMs * (ASR_PCM_SAMPLE_RATE / 1_000),
                rangeEndSample = endMs?.times(ASR_PCM_SAMPLE_RATE / 1_000),
                chunkSamples = chunkSamples,
            ) {
                checkCancelled()
                onChunk(it)
            }

            fun finishPcmSegment(): Boolean {
                val current = resampler
                if (current != null && chunks.isActive) {
                    val sample = segmentStartSample + current.outputSampleCount
                    if (!chunks.append(current.finish(), sample)) return false
                }
                return chunks.finishSegment()
            }

            val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            var outputDone = false
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var queuedMedia = false
                while (!outputDone && chunks.isActive) {
                    checkCancelled()
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex))
                            var sample = extractor.nextSample()
                            // Unindexed sources may still require reading earlier compressed frames;
                            // they do not need codec decoding or resampling before the preroll.
                            val prerollUs = (startMs - 1_000).coerceAtLeast(0) * 1_000
                            var skippedMedia = false
                            while (sequentialFallback && sample != null && sample.timeUs < prerollUs) {
                                skippedMedia = true
                                checkCancelled()
                                sample = extractor.nextSample()
                            }
                            if (sample == null) {
                                if (skippedMedia && !queuedMedia) {
                                    // No PCM was decoded, so a zero endpoint cannot establish that
                                    // saved subtitles belong to a different recording.
                                    throw IOException("Podcast audio ended before the requested recognition range")
                                }
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                check(sample.data.size <= inputBuffer.remaining()) { "Podcast sample exceeds codec input capacity" }
                                inputBuffer.put(sample.data)
                                queuedMedia = true
                                codec.queueInputBuffer(inputIndex, 0, sample.data.size, sample.timeUs, 0)
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outputIndex >= 0) {
                            var pcm16: ShortArray? = null
                            var pcmFloat: FloatArray? = null
                            val presentationTimeUs = bufferInfo.presentationTimeUs
                            try {
                                val size = bufferInfo.size
                                if (size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val buffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + size)
                                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                                    when (pcmEncoding) {
                                        AudioFormat.ENCODING_PCM_16BIT -> {
                                            check(size % Short.SIZE_BYTES == 0) { "Incomplete PCM16 sample" }
                                            pcm16 = ShortArray(size / Short.SIZE_BYTES).also { buffer.asShortBuffer().get(it) }
                                        }
                                        AudioFormat.ENCODING_PCM_FLOAT -> {
                                            check(size % Float.SIZE_BYTES == 0) { "Incomplete float PCM sample" }
                                            pcmFloat = FloatArray(size / Float.SIZE_BYTES).also { buffer.asFloatBuffer().get(it) }
                                        }
                                        else -> error("Unsupported PCM encoding: $pcmEncoding")
                                    }
                                }
                            } finally {
                                // Native inference can take much longer than copying one decoder buffer.
                                codec.releaseOutputBuffer(outputIndex, false)
                            }

                            val sampleCount = pcm16?.size ?: pcmFloat?.size ?: 0
                            if (sampleCount > 0) {
                                check(channelCount > 0 && sampleCount % channelCount == 0) { "Incomplete PCM channel frame" }
                                val position = clock.locate(presentationTimeUs, sampleCount / channelCount, sampleRate, channelCount)
                                if (position.startsNewSegment) {
                                    if (!finishPcmSegment()) break
                                    resampler = StreamingPcmResampler(sampleRate, channelCount)
                                    segmentStartSample = position.segmentStartSample
                                }
                                val skipSamples = position.skipFrames * channelCount
                                if (skipSamples < sampleCount) {
                                    val current = checkNotNull(resampler)
                                    val firstSample = segmentStartSample + current.outputSampleCount
                                    val samples16 = pcm16
                                    val samplesFloat = pcmFloat
                                    val resampled = if (samples16 != null) {
                                        current.process(if (skipSamples == 0) samples16 else samples16.copyOfRange(skipSamples, sampleCount))
                                    } else {
                                        checkNotNull(samplesFloat)
                                        current.process(if (skipSamples == 0) samplesFloat else samplesFloat.copyOfRange(skipSamples, sampleCount))
                                    }
                                    if (!chunks.append(resampled, firstSample)) break
                                }
                            }
                            outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        }
                    }
                }
                checkCancelled()
                if (chunks.isActive) finishPcmSegment()
                return DecodeResult(
                    endSample = if (chunks.reachedRangeEnd && endMs != null) {
                        endMs * (ASR_PCM_SAMPLE_RATE / 1_000)
                    } else {
                        (chunks.emittedEndSample ?: clock.endSample).coerceAtLeast(0L)
                    },
                    reachedRangeEnd = chunks.reachedRangeEnd,
                    reachedEndOfInput = outputDone,
                    stoppedEarly = chunks.stoppedEarly,
                )
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        }

        override fun close() {
            if (!closed) {
                check(!decoding) { "Cannot close an active audio decoder" }
                closed = true
                try {
                    extractor.close()
                } finally {
                    onClosed()
                }
            }
        }
    }
}

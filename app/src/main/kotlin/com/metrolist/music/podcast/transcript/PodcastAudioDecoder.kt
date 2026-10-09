/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.metrolist.music.podcast.PODCAST_USER_AGENT
import java.io.Closeable
import java.nio.ByteOrder

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

    fun open(source: String, checkCancelled: () -> Unit = {}): Session {
        checkCancelled()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source, mapOf("User-Agent" to PODCAST_USER_AGENT))
            checkCancelled()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    extractor.selectTrack(index)
                    return Session(extractor, format, extractor.sampleTime)
                }
            }
            error("No audio track")
        } catch (error: Throwable) {
            extractor.release()
            throw error
        }
    }

    /** Used serially by one recognition task; seeking retains this source's extractor. */
    class Session internal constructor(
        private val extractor: MediaExtractor,
        private val format: MediaFormat,
        private val initialSampleTimeUs: Long,
    ) : Closeable {
        // Track metadata is a hint, never the cutoff for decoding the episode's tail.
        val durationMs: Long = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L) / 1_000
        } else 0L

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
                onSeek(seek(startMs, checkCancelled))
                checkCancelled()
                return decodePositionedRange(startMs, endMs, chunkSamples, checkCancelled, onChunk)
            } finally {
                decoding = false
            }
        }

        private fun seek(startMs: Long, checkCancelled: () -> Unit): SeekInfo {
            val requestedUs = startMs * 1_000
            var seekFailed = false
            try {
                extractor.seekTo(requestedUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            } catch (_: Exception) {
                checkCancelled()
                seekFailed = true
            }
            checkCancelled()
            var actualUs = extractor.sampleTime
            var usedFallback = startMs > 0 && actualUs == initialSampleTimeUs && actualUs < requestedUs
            if (seekFailed || actualUs < 0 || actualUs > requestedUs && actualUs != initialSampleTimeUs) {
                // Never reopen the URL here: a second request can return different dynamic ads.
                // An extractor that cannot safely rewind must fail instead of inventing an offset.
                try {
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                } catch (error: Exception) {
                    checkCancelled()
                    check(extractor.sampleTime == initialSampleTimeUs) {
                        "Audio source cannot return to a known media position: ${error.javaClass.simpleName}"
                    }
                }
                checkCancelled()
                actualUs = extractor.sampleTime
                check(actualUs == initialSampleTimeUs) { "Audio source cannot return to its beginning" }
                usedFallback = true
            }
            return SeekInfo(startMs, actualUs.coerceAtLeast(0L) / 1_000, usedFallback)
        }

        private fun decodePositionedRange(
            startMs: Long,
            endMs: Long?,
            chunkSamples: Int,
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
                while (!outputDone && chunks.isActive) {
                    checkCancelled()
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex))
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                extractor.release()
            }
        }
    }
}

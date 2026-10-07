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
import java.nio.ByteOrder

/** Streams an audio URL into 16 kHz mono float chunks via the platform decoder. */
internal object PodcastAudioDecoder {
    private const val SAMPLE_RATE = 16_000

    fun decodeToPcmChunks(
        source: String,
        chunkSamples: Int,
        checkCancelled: () -> Unit = {},
        onChunk: (FloatArray, Int) -> Unit,
    ) {
        require(chunkSamples > 0) { "Chunk size must be positive" }
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
            check(trackIndex >= 0 && format != null) { "No audio track" }
            extractor.selectTrack(trackIndex)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamingPcmResampler(sampleRate, channelCount)
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
                            checkCancelled()
                            onChunk(carry.copyOf(), SAMPLE_RATE)
                            carrySize = 0
                        }
                    }
                }

                while (!outputDone) {
                    checkCancelled()
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
                            val nextSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val nextChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (nextSampleRate != sampleRate || nextChannelCount != channelCount) {
                                check(!resampler.hasInput) { "PCM format changed during recognition" }
                                sampleRate = nextSampleRate
                                channelCount = nextChannelCount
                                resampler = StreamingPcmResampler(sampleRate, channelCount)
                            }
                            pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outputIndex >= 0) {
                            val resampled = try {
                                val size = bufferInfo.size
                                if (size > 0) {
                                    val outputBuffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + size)
                                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                    when (pcmEncoding) {
                                        AudioFormat.ENCODING_PCM_16BIT -> {
                                            check(size % Short.SIZE_BYTES == 0) { "Incomplete PCM16 sample" }
                                            val samples = ShortArray(size / Short.SIZE_BYTES)
                                            outputBuffer.asShortBuffer().get(samples)
                                            resampler.process(samples)
                                        }
                                        AudioFormat.ENCODING_PCM_FLOAT -> {
                                            check(size % Float.SIZE_BYTES == 0) { "Incomplete float PCM sample" }
                                            val samples = FloatArray(size / Float.SIZE_BYTES)
                                            outputBuffer.asFloatBuffer().get(samples)
                                            resampler.process(samples)
                                        }
                                        else -> error("Unsupported PCM encoding: $pcmEncoding")
                                    }
                                } else {
                                    FloatArray(0)
                                }
                            } finally {
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                            feed(resampled)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                feed(resampler.finish())
                checkCancelled()
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

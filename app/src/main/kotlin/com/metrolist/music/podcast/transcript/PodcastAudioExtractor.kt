/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.Closeable
import java.io.EOFException
import java.io.IOException

/** Reads compressed audio with the same seek maps used by playback, without a second player. */
internal class PodcastAudioExtractor(
    private val dataSource: DataSource,
    private var dataSpec: DataSpec,
    private var checkCancelled: () -> Unit = {},
) : Closeable {
    data class Sample(val timeUs: Long, val flags: Int, val data: ByteArray)
    data class SeekPosition(val requestedMs: Long, val actualMs: Long, val sequential: Boolean)

    private var extractor: Extractor? = null
    private var input: DefaultExtractorInput? = null
    private var seekMap: SeekMap? = null
    private var documentLength: Long? = null
    private var validator: Pair<String, String>? = null
    private var ended = false
    private val positionHolder = PositionHolder()
    private val audio = AudioOutput()
    private val output = object : ExtractorOutput {
        private var audioTrackId: Int? = null
        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_AUDIO) return DiscardingTrackOutput()
            if (audioTrackId == null) audioTrackId = id
            return if (audioTrackId == id) audio else DiscardingTrackOutput()
        }
        override fun endTracks() = Unit
        override fun seekMap(value: SeekMap) { seekMap = value }
    }

    val format: Format get() = checkNotNull(audio.format)
    val durationMs: Long get() = seekMap?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1_000) ?: 0L

    fun prepare() {
        check(extractor == null)
        try {
            reopen(0)
            val candidates = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .createExtractors(dataSpec.uri, dataSource.responseHeaders)
            val selected = candidates.firstOrNull { candidate ->
                checkCancelled()
                try {
                    candidate.sniff(checkNotNull(input))
                } catch (_: EOFException) {
                    false
                } finally {
                    input?.resetPeekPosition()
                }
            }
            candidates.filter { it !== selected }.forEach { it.release() }
            extractor = checkNotNull(selected) { "Unsupported podcast audio container" }
            selected.init(output)
            while (audio.format == null || seekMap == null) {
                check(!ended) { "No podcast audio track or seek map" }
                readExtractor()
            }
            check(MimeTypes.isAudio(format.sampleMimeType))
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun seek(positionMs: Long, checkCancelled: () -> Unit): SeekPosition {
        require(positionMs in 0..Long.MAX_VALUE / 1_000)
        this.checkCancelled = checkCancelled
        checkCancelled()
        val map = checkNotNull(seekMap)
        val point = map.getSeekPoints(if (map.isSeekable) positionMs * 1_000 else 0L).first
        audio.clear()
        ended = false
        // Reopen at the mapped byte position, not at the beginning of the episode. The resolved
        // URL and HTTP validator are retained so a redirect cannot select another ad insertion.
        reopen(point.position)
        checkNotNull(extractor).seek(point.position, point.timeUs)
        return SeekPosition(positionMs, point.timeUs.coerceAtLeast(0) / 1_000, !map.isSeekable && positionMs > 0)
    }

    fun nextSample(): Sample? {
        while (audio.samples.isEmpty() && !ended) readExtractor()
        checkCancelled()
        return audio.takeSample()
    }

    private fun readExtractor() {
        checkCancelled()
        when (checkNotNull(extractor).read(checkNotNull(input), positionHolder)) {
            Extractor.RESULT_END_OF_INPUT -> ended = true
            Extractor.RESULT_SEEK -> reopen(positionHolder.position)
        }
        checkCancelled()
    }

    private fun reopen(position: Long) {
        checkCancelled()
        dataSource.close()
        val headers = dataSpec.httpRequestHeaders + validator?.let { (name, value) -> mapOf(name to value) }.orEmpty()
        val remaining = try {
            dataSource.open(dataSpec.buildUpon().setPosition(position).setHttpRequestHeaders(headers).build())
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode == 412 && validator != null) throw AsrRecordingChangedException()
            throw error
        }
        checkCancelled()
        val responseHeaders = dataSource.responseHeaders
        fun header(name: String) = responseHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        header("Content-Range")?.let { range ->
            val actualPosition = range.removePrefix("bytes ").substringBefore('-').toLongOrNull()
            check(actualPosition == position) { "Podcast server returned a different byte range" }
        }
        val length = if (remaining == C.LENGTH_UNSET.toLong()) null else position + remaining
        val previousLength = documentLength
        if (previousLength != null && length != null && previousLength != length) throw AsrRecordingChangedException()
        val etag = header("ETag")?.takeUnless { it.startsWith("W/") }
        val currentValidator = etag?.let { "If-Match" to it }
            ?: header("Last-Modified")?.let { "If-Unmodified-Since" to it }
        if (validator != null && currentValidator != null && validator != currentValidator) throw AsrRecordingChangedException()
        if (documentLength == null) documentLength = length
        if (validator == null) validator = currentValidator
        dataSource.uri?.let { resolved -> dataSpec = dataSpec.withUri(resolved) }
        var readPosition = position
        input = DefaultExtractorInput(
            DataReader { buffer, offset, count ->
                checkCancelled()
                val read = dataSource.read(buffer, offset, count)
                checkCancelled()
                if (read == C.RESULT_END_OF_INPUT && documentLength?.let { readPosition < it } == true) {
                    // A dropped transfer is retryable; it does not prove a different recording.
                    throw IOException("Podcast audio response ended before its declared length")
                }
                if (read > 0) readPosition += read
                read
            },
            position,
            documentLength ?: C.LENGTH_UNSET.toLong(),
        )
    }

    override fun close() {
        try {
            extractor?.release()
        } finally {
            extractor = null
            input = null
            audio.clear()
            dataSource.close()
        }
    }

    private class AudioOutput : TrackOutput {
        var format: Format? = null
            private set
        val samples = ArrayDeque<Sample>()
        private var pending = ByteArray(8_192)
        private var written = 0
        private var queuedBytes = 0

        override fun format(format: Format) { this.format = format }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            check(sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN)
            val count = minOf(length, 64 * 1_024)
            ensureCapacity(count)
            val read = input.read(pending, written, count)
            if (read == C.RESULT_END_OF_INPUT) {
                if (!allowEndOfInput) throw EOFException()
            } else {
                written += read
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            check(sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN)
            ensureCapacity(length)
            data.readBytes(pending, written, length)
            written += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            check(cryptoData == null && flags and C.BUFFER_FLAG_ENCRYPTED == 0) { "Encrypted podcast audio is unsupported" }
            val end = written - offset
            check(size >= 0 && offset >= 0 && end >= size)
            check(queuedBytes + size <= MAX_PENDING_BYTES) { "Podcast audio sample queue exceeds its limit" }
            samples.addLast(Sample(timeUs, flags, pending.copyOfRange(end - size, end)))
            queuedBytes += size
            pending.copyInto(pending, 0, end, written)
            written = offset
        }

        fun takeSample(): Sample? = samples.removeFirstOrNull()?.also { queuedBytes -= it.data.size }

        fun clear() {
            samples.clear()
            written = 0
            queuedBytes = 0
        }

        private fun ensureCapacity(extra: Int) {
            check(extra >= 0 && written <= MAX_PENDING_BYTES - extra) { "Podcast audio sample exceeds its limit" }
            val needed = written + extra
            if (needed > pending.size) pending = pending.copyOf(maxOf(needed, pending.size * 2).coerceAtMost(MAX_PENDING_BYTES))
        }
    }

    private companion object {
        const val MAX_PENDING_BYTES = 4 * 1_024 * 1_024
    }
}

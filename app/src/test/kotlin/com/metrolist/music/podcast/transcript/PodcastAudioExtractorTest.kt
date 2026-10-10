/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.Application
import androidx.core.net.toUri
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PodcastAudioExtractorTest {
    @Test
    fun `existing podcast containers retain their audio format and absolute seek timestamps`() {
        // Public AndroidX Media 1.10.1 extractor fixtures, Apache-2.0; original files live in
        // libraries/test_data/src/test/assets/media/{aac,mp4,ogg,flac,mp3} of androidx/media.
        val formats = mapOf(
            "sample.aac" to "audio/mp4a-latm",
            "sample.m4a" to "audio/mp4a-latm",
            "sample.opus" to "audio/opus",
            "sample.ogg" to "audio/vorbis",
            "sample.flac" to "audio/flac",
            "xing.mp3" to "audio/mpeg",
            "vbri.mp3" to "audio/mpeg",
        )
        for ((name, mime) in formats) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/podcast-audio/$name")).use { it.readBytes() }
            PodcastAudioExtractor(ByteArrayDataSource(bytes), DataSpec("memory://audio/$name".toUri())).use { reader ->
                reader.prepare()
                assertEquals(name, mime, reader.format.sampleMimeType)
                assertTrue(name, checkNotNull(reader.nextSample()).data.isNotEmpty())
                val target = if (reader.durationMs > 0) reader.durationMs / 2 else 1_000L
                val seek = reader.seek(target) {}
                val sample = checkNotNull(reader.nextSample())
                assertTrue("$name: ${sample.timeUs} after ${seek.actualMs}", sample.timeUs >= 0)
                if (!seek.sequential) {
                    assertTrue("$name: ${sample.timeUs} vs $target", abs(sample.timeUs / 1_000 - target) < 1_200)
                }
                reader.seek(0) {}
                assertTrue(name, checkNotNull(reader.nextSample()).timeUs < 250_000)
            }
        }
    }

    @Test
    fun `PCM podcast range decoding preserves the requested time and never decodes its earlier prefix`() {
        val rate = 16_000
        val samples = 40 * rate
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { wav.putShort((it % 32768).toShort()) }
        val extractor = PodcastAudioExtractor(ByteArrayDataSource(wav.array()), DataSpec("memory://audio/sample.wav".toUri()))
        extractor.prepare()
        PodcastAudioDecoder.Session(extractor, MediaFormatUtil.createMediaFormatFromFormat(extractor.format)).use { decoder ->
            val chunks = mutableListOf<TimedPcmChunk>()
            val result = decoder.decodeRange(20_000, 22_000, onChunk = { chunks += it; true })
            assertTrue(result.reachedRangeEnd)
            assertFalse(result.reachedEndOfInput)
            assertEquals(20_000, chunks.first().startMs)
            assertEquals(22_000, chunks.last().endMs)
            assertEquals(2 * rate, chunks.sumOf { it.samples.size })
            assertEquals((20 * rate % 32768) / 32768f, chunks.first().samples.first(), 0.002f)
            val beyondEnd = decoder.decodeRange(60_000, onChunk = { throw AssertionError("PCM before requested range emitted") })
            assertTrue(beyondEnd.reachedEndOfInput)
            // Container timestamps have microsecond precision; converting the tail back to
            // 16 kHz samples can round down by one sample (less than 0.1 ms).
            assertTrue("Unexpected PCM endpoint: ${beyondEnd.endSample}", abs(beyondEnd.endSample - 40_000L * 16) <= 1)
            verifyAsrMediaEnd(beyondEnd.endSample / 16, listOf(AsrAudioRange(20_000, 22_000)), emptyList())
        }
    }

    @Test
    fun `cancelling the owner interrupts a blocked response before the network timeout`() {
        AudioServer(ByteArray(0), stallResponse = true).use { server ->
            val owner = Job()
            val executor = Executors.newSingleThreadExecutor()
            val context = RuntimeEnvironment.getApplication()
            val result = executor.submit(Callable {
                runCatching {
                    PodcastAudioDecoder.open(context, server.url, owner) { owner.ensureActive() }.use { }
                }
            })
            try {
                assertTrue("Request was not opened", server.requestReceived.await(5, TimeUnit.SECONDS))
                owner.cancel()
                assertNotNull(result.get(5, TimeUnit.SECONDS).exceptionOrNull())
                assertTrue(owner.children.none())
            } finally {
                owner.cancel()
                result.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `an incomplete transfer fails as a read error instead of a changed recording`() {
        val source = ByteArrayDataSource(mp3())
        val truncated = object : androidx.media3.datasource.DataSource by source {
            override fun open(dataSpec: DataSpec): Long = source.open(dataSpec) + 1_000
        }
        PodcastAudioExtractor(truncated, DataSpec("memory://audio/truncated.mp3".toUri())).use { reader ->
            reader.prepare()
            try {
                while (reader.nextSample() != null) { }
                throw AssertionError("Premature end of transfer accepted")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("declared length"))
            }
        }
    }

    @Test
    fun `a failed conditional request is treated as a changed recording`() {
        AudioServer(mp3()).use { server ->
            reader(server.url).use { reader ->
                reader.prepare()
                server.preconditionFailed = true
                try {
                    reader.seek(600_000) {}
                    throw AssertionError("Changed recording accepted")
                } catch (_: AsrRecordingChangedException) {
                    assertEquals("\"recording-1\"", server.conditions.last())
                }
            }
        }
    }

    @Test
    fun `a response with the wrong byte range cannot replace subtitle audio`() {
        AudioServer(mp3()).use { server ->
            reader(server.url).use { reader ->
                reader.prepare()
                server.wrongRange = true
                try {
                    reader.seek(600_000) {}
                    throw AssertionError("Wrong range accepted")
                } catch (error: IllegalStateException) {
                    assertTrue(error.message.orEmpty().contains("different byte range"))
                }
            }
        }
    }

    @Test
    fun `ten minute seek and resume read a mapped byte range with original media timestamps`() {
        AudioServer(mp3()).use { server ->
            val reader = reader(server.url)
            reader.use {
                it.prepare()
                assertEquals("audio/mpeg", it.format.sampleMimeType)
                assertTrue(it.durationMs > 1_200_000)
                val first = it.seek(600_000) {}
                assertFalse(first.sequential)
                assertTrue(abs(first.actualMs - 600_000) < 50)
                assertTrue(abs(checkNotNull(it.nextSample()).timeUs - 600_000_000) < 50_000)
                val second = it.seek(615_000) {}
                assertFalse(second.sequential)
                assertTrue(abs(checkNotNull(it.nextSample()).timeUs - 615_000_000) < 50_000)
                it.seek(20_000) {}
                assertTrue(abs(checkNotNull(it.nextSample()).timeUs - 20_000_000) < 50_000)
                assertTrue(server.ranges.any { it > 2_000_000 })
                assertTrue(server.conditions.drop(1).all { it == "\"recording-1\"" })
            }
        }
    }

    @Test
    fun `a redirect is resolved once so seeking cannot request another dynamic recording`() {
        AudioServer(mp3(), redirect = true).use { server ->
            reader(server.url).use {
                it.prepare()
                it.seek(600_000) {}
                assertTrue(checkNotNull(it.nextSample()).timeUs > 599_000_000)
                it.seek(10_000) {}
                assertEquals(1, server.redirects.size)
                assertTrue(server.paths.drop(1).all { path -> path == "/recording.mp3" })
            }
        }
    }

    @Test
    fun `a changed source length is rejected before its frames can replace saved subtitles`() {
        AudioServer(mp3()).use { server ->
            reader(server.url).use {
                it.prepare()
                server.truncate = true
                try {
                    it.seek(600_000) {}
                    throw AssertionError("Changed recording accepted")
                } catch (_: AsrRecordingChangedException) {
                    assertTrue(server.ranges.last() > 0)
                }
            }
        }
    }

    @Test
    fun `a changed validator is rejected even when the recording length stays the same`() {
        AudioServer(mp3()).use { server ->
            reader(server.url).use {
                it.prepare()
                server.changeValidator = true
                try {
                    it.seek(600_000) {}
                    throw AssertionError("Changed recording accepted")
                } catch (_: AsrRecordingChangedException) {
                    assertTrue(server.conditions.last() == "\"recording-1\"")
                }
            }
        }
    }

    @Test
    fun `repositioning checks cancellation before opening another network request`() {
        AudioServer(mp3()).use { server ->
            reader(server.url).use {
                it.prepare()
                val before = server.ranges.size
                try {
                    it.seek(600_000) { throw kotlinx.coroutines.CancellationException("cancelled") }
                    throw AssertionError("Cancellation ignored")
                } catch (_: kotlinx.coroutines.CancellationException) {
                    assertEquals(before, server.ranges.size)
                }
            }
        }
    }

    private fun reader(url: String) = PodcastAudioExtractor(
        OkHttpDataSource.Factory(OkHttpClient()).createDataSource(),
        DataSpec(url.toUri()),
    )

    /** Valid constant-bitrate MPEG headers without a seek table, as in many RSS MP3 feeds. */
    private fun mp3(): ByteArray {
        val frame = ByteArray(104)
        byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x10, 0x64).copyInto(frame)
        return ByteArray(frame.size * 50_000) { frame[it % frame.size] }
    }

    private class AudioServer(
        private val bytes: ByteArray,
        private val redirect: Boolean = false,
        private val stallResponse: Boolean = false,
    ) : Closeable {
        val requestReceived = CountDownLatch(1)
        private val releaseResponse = CountDownLatch(1)
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val active = AtomicBoolean(true)
        val ranges = CopyOnWriteArrayList<Long>()
        val conditions = CopyOnWriteArrayList<String?>()
        val paths = CopyOnWriteArrayList<String>()
        val redirects = CopyOnWriteArrayList<String>()
        @Volatile var truncate = false
        @Volatile var changeValidator = false
        @Volatile var preconditionFailed = false
        @Volatile var wrongRange = false
        val url = "http://127.0.0.1:${server.localPort}/${if (redirect) "dynamic.mp3" else "recording.mp3"}"
        private val worker = thread(isDaemon = true, name = "podcast-audio-test") {
            while (active.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { socket.use(::respond) }
            }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val path = input.readLine().split(' ')[1]
            paths += path
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val output = socket.getOutputStream()
            requestReceived.countDown()
            if (stallResponse) {
                releaseResponse.await(10, TimeUnit.SECONDS)
                return
            }
            if (path == "/dynamic.mp3") {
                redirects += path
                output.write("HTTP/1.1 302 Found\r\nLocation: /recording.mp3\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            val start = headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
            ranges += start.toLong()
            conditions += headers["if-match"]
            if (preconditionFailed) {
                output.write("HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            val end = bytes.size - if (truncate) 20_000 else 0
            val length = end - start
            val rangeStart = if (wrongRange) start + 100 else start
            val rangeHeader = if (start > 0) "Content-Range: bytes $rangeStart-${end - 1}/$end\r\n" else ""
            val status = if (start > 0) "206 Partial Content" else "200 OK"
            val etag = if (changeValidator) "recording-2" else "recording-1"
            output.write(("HTTP/1.1 $status\r\nContent-Type: audio/mpeg\r\nContent-Length: $length\r\n" +
                rangeHeader + "Accept-Ranges: bytes\r\nETag: \"$etag\"\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(bytes, start, length)
            output.flush()
        }

        override fun close() {
            active.set(false)
            releaseResponse.countDown()
            server.close()
            worker.join(5_000)
        }
    }
}

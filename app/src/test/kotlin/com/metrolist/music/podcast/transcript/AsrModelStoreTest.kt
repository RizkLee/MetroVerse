/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.R
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class AsrModelStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var files: File
    private lateinit var model: File
    private lateinit var store: AsrModelStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        files = temporaryFolder.newFolder("files")
        val application = ApplicationProvider.getApplicationContext<Application>()
        val errorResources = ModelErrorResources(application.resources)
        context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = files
            override fun getResources(): Resources = errorResources
        }
        val bytes = "supported-model".toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        store = AsrModelStore(context, "test-model", listOf(AsrModelArtifact("model.ort", bytes.size.toLong(), sha256, emptyList())))
        model = files.resolve("podcast/asr/test-model/model.ort").apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) }
    }

    @Test
    fun `only files with the supported size and digest are ready`() {
        assertTrue(store.isReady())
        val modified = model.lastModified()
        model.writeBytes(ByteArray(model.length().toInt()) { 'x'.code.toByte() })
        assertTrue(model.setLastModified(modified + 1_000))
        assertFalse(store.isReady())
    }

    @Test
    fun `deleting the selected model preserves the rollback model`() = runBlocking {
        val legacy = files.resolve("podcast/asr/zipformer-en/encoder.int8.onnx").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("rollback model")
        }
        assertTrue(store.delete())
        assertFalse(model.exists())
        assertEquals("rollback model", legacy.readText())
    }

    @Test
    fun `deletion waits for the active native operation to release its model`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val use = async {
            store.withModel {
                entered.complete(Unit)
                release.await()
                assertTrue(model.exists())
            }
        }
        entered.await()
        val deletion = async(start = CoroutineStart.UNDISPATCHED) { store.delete() }
        yield()
        assertFalse(deletion.isCompleted)
        release.complete(Unit)
        use.await()
        assertTrue(deletion.await())
        assertFalse(model.exists())
    }

    @Test
    fun `moonshine manifest is exactly the eight verified files below the download cap`() {
        assertEquals(8, MoonshineModelFiles.artifacts.size)
        assertEquals(8, MoonshineModelFiles.artifacts.map { it.name }.distinct().size)
        assertEquals(142_300_974L, MoonshineModelFiles.artifacts.sumOf { it.bytes })
        assertTrue(MoonshineModelFiles.artifacts.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
        assertTrue(MoonshineModelFiles.artifacts.none { "attention" in it.name })
    }

    @Test
    fun `model errors preserve localized messages and hide provider diagnostics`() {
        val known = AsrModelPreparationException("Localized HTTP failure")
        assertSame(known, localizedModelFailure(known, "Generic localized failure"))
        val diagnostic = IOException("Provider internal English error")
        val mapped = localizedModelFailure(diagnostic, "Generic localized failure")
        assertEquals("Generic localized failure", mapped.message)
        assertSame(diagnostic, mapped.cause)
    }

    @Test
    fun `verified download commits the model and reports it ready`() = runBlocking {
        val supported = "downloaded-model".toByteArray()
        assertTrue(model.delete())
        LocalModelServer(listOf(supported)).use { server ->
            val downloading = downloadStore(supported, server.url)
            val progress = mutableListOf<Pair<Long, Long>>()
            val result = downloading.download { bytes, total -> progress += bytes to total }
            assertTrue(result.toString(), result.isSuccess)
            assertTrue(downloading.isReady())
            assertTrue(supported.contentEquals(model.readBytes()))
            assertFalse(File(model.path + ".part").exists())
            assertEquals(supported.size.toLong() to supported.size.toLong(), progress.last())
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun `bad digest and length preserve the previous file and a later retry succeeds`() = runBlocking {
        val supported = "replacement-model".toByteArray()
        val previous = model.readBytes()
        val wrongDigest = ByteArray(supported.size) { 'x'.code.toByte() }
        val truncated = supported.copyOf(supported.size - 1)
        LocalModelServer(listOf(wrongDigest, truncated, supported)).use { server ->
            val downloading = downloadStore(supported, server.url)
            repeat(2) {
                val result = downloading.download { _, _ -> }
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is AsrModelPreparationException)
                assertTrue(previous.contentEquals(model.readBytes()))
                assertFalse(File(model.path + ".part").exists())
                assertFalse(downloading.isReady())
            }
            assertTrue(downloading.download { _, _ -> }.isSuccess)
            assertTrue(downloading.isReady())
            assertTrue(supported.contentEquals(model.readBytes()))
            assertFalse(File(model.path + ".part").exists())
            assertEquals(3, server.requests.get())
        }
    }

    private fun downloadStore(bytes: ByteArray, url: String): AsrModelStore {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return AsrModelStore(context, "test-model", listOf(AsrModelArtifact("model.ort", bytes.size.toLong(), sha256, listOf(url))))
    }

    @Test
    fun `cancelling a blocked provider read closes it and releases model ownership`() = runBlocking {
        val input = BlockingProviderStream()
        val operation = async(Dispatchers.IO) {
            store.withModel {
                withAsrSafCancellation(cancel = input::close) { input.read() }
            }
        }
        try {
            assertTrue(input.readEntered.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { operation.cancelAndJoin() }
            assertTrue(operation.isCancelled)
            assertTrue(input.closed.get())
            assertTrue(withTimeout(5_000) { store.delete() })
        } finally {
            input.close()
            operation.cancelAndJoin()
        }
    }

    @Test
    fun `provider preparation timeout unblocks its read and propagates cancellation`() = runBlocking {
        val input = BlockingProviderStream()
        val operation = async(Dispatchers.IO) {
            try {
                withAsrSafCancellation(timeoutMillis = 250, cancel = input::close) { input.read() }
                false
            } catch (_: TimeoutCancellationException) {
                true
            }
        }
        try {
            assertTrue(input.readEntered.await(5, TimeUnit.SECONDS))
            assertTrue(withTimeout(5_000) { operation.await() })
            assertTrue(input.closed.get())
        } finally {
            input.close()
            operation.cancelAndJoin()
        }
    }

    private class BlockingProviderStream : InputStream() {
        val readEntered = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val releaseRead = CountDownLatch(1)

        override fun read(): Int {
            readEntered.countDown()
            check(releaseRead.await(10, TimeUnit.SECONDS)) { "Test provider read was not closed" }
            throw IOException("Provider descriptor was closed")
        }

        override fun close() {
            closed.set(true)
            releaseRead.countDown()
        }
    }

    // This manifest-free JVM test has no application resource APK; only error formatting is stubbed.
    @Suppress("DEPRECATION")
    private class ModelErrorResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getString(id: Int): String = when (id) {
            R.string.podcast_asr_model_download_failed,
            R.string.podcast_asr_model_file_invalid,
            R.string.podcast_asr_model_files_missing,
            R.string.podcast_subtitle_asr_model_failed,
            R.string.podcast_subtitle_asr_model_corrupt -> "Localized model error"
            else -> super.getString(id)
        }

        override fun getString(id: Int, vararg formatArgs: Any): String = getString(id)
    }

    private class LocalModelServer(private val responses: List<ByteArray>) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val active = AtomicReference<Socket?>()
        private val closed = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>()
        val requests = AtomicInteger(0)
        val url = "http://127.0.0.1:${server.localPort}/model.ort"
        private val worker = thread(isDaemon = true, name = "model-download-test") {
            try {
                server.soTimeout = 10_000
                responses.forEach { payload ->
                    server.accept().use { socket ->
                        active.set(socket)
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        requests.incrementAndGet()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            write(payload)
                            flush()
                        }
                        active.compareAndSet(socket, null)
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) failure.set(error)
            } finally {
                server.close()
            }
        }

        override fun close() {
            closed.set(true)
            active.get()?.close()
            server.close()
            worker.join(5_000)
            check(!worker.isAlive) { "Local test server did not stop" }
            failure.get()?.let { throw AssertionError("Local model server failed", it) }
        }
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import com.metrolist.music.R
import com.metrolist.music.podcast.PODCAST_USER_AGENT
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AsrModelArtifact(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val urls: List<String>,
)

internal class AsrModelPreparationException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun localizedModelFailure(error: Exception, fallback: String): AsrModelPreparationException =
    if (error is AsrModelPreparationException) error else AsrModelPreparationException(fallback, error)

/** Unblocks provider I/O on cancellation and waits for cleanup before releasing model ownership. */
internal suspend fun <T> withAsrSafCancellation(
    timeoutMillis: Long = 300_000,
    cancel: () -> Unit,
    block: suspend () -> T,
): T = withContext(Dispatchers.IO) {
    withTimeout(timeoutMillis) {
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { cancel() }
            }
            try {
                block()
            } catch (error: Exception) {
                // A closed provider stream commonly reports IOException instead of cancellation.
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                withContext(NonCancellable) { cancellation.cancelAndJoin() }
            }
        }
    }
}

/** Serializes model mutations with native use; never owns another backend's model directory. */
internal class AsrModelStore(
    private val context: Context,
    directoryName: String,
    private val artifacts: List<AsrModelArtifact>,
) {
    private val directory = File(context.filesDir, "podcast/asr/$directoryName")
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    @Volatile private var validatedFingerprint: List<Pair<Long, Long>>? = null

    val downloadBytes: Long get() = artifacts.sumOf { it.bytes }

    fun isReady(): Boolean {
        val files = artifacts.map { directory.resolve(it.name) }
        if (files.zip(artifacts).any { (file, artifact) -> !file.isFile || file.length() != artifact.bytes }) return false
        val fingerprint = files.map { it.length() to it.lastModified() }
        if (validatedFingerprint == fingerprint) return true
        val valid = files.zip(artifacts).all { (file, artifact) -> validFile(file, artifact) }
        validatedFingerprint = fingerprint.takeIf { valid }
        return valid
    }

    fun storageBytes(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    suspend fun delete(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            validatedFingerprint = null
            val deleted = artifacts.flatMap { listOf(directory.resolve(it.name), directory.resolve("${it.name}.part")) }
                .map { !it.exists() || it.delete() }.all { it }
            if (directory.listFiles()?.isEmpty() == true) directory.delete()
            deleted
        }
    }

    suspend fun invalidateInvalidFiles(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReady()) return@withLock false
            validatedFingerprint = null
            artifacts.forEach { artifact ->
                directory.resolve(artifact.name).takeIf { it.exists() && !validFile(it, artifact) }?.delete()
            }
            true
        }
    }

    suspend fun <T> withModel(block: suspend (File) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isReady()) throw AsrModelPreparationException(context.getString(R.string.podcast_subtitle_asr_model_corrupt))
        }
        block(directory)
    }

    suspend fun download(onProgress: (Long, Long) -> Unit): Result<Unit> = modelOperation {
        var completed = 0L
        artifacts.forEach { artifact ->
            currentCoroutineContext().ensureActive()
            val target = directory.resolve(artifact.name)
            if (!validFile(target, artifact)) {
                var lastError: Exception? = null
                for (url in artifact.urls) {
                    try {
                        val request = Request.Builder().url(url).header("User-Agent", PODCAST_USER_AGENT).build()
                        val call = client.newCall(request)
                        coroutineScope {
                            // Cancel the blocking socket read as soon as its owning operation is cancelled.
                            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                                try { awaitCancellation() } finally { call.cancel() }
                            }
                            try {
                                call.execute().use { response ->
                                    if (!response.isSuccessful) {
                                        throw AsrModelPreparationException(context.getString(R.string.podcast_asr_model_download_failed, response.code))
                                    }
                                    val body = checkNotNull(response.body)
                                    body.byteStream().use { input -> copyVerified(input, artifact) { onProgress(completed + it, downloadBytes) } }
                                }
                            } finally {
                                withContext(NonCancellable) { cancellation.cancelAndJoin() }
                            }
                        }
                        lastError = null
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        lastError = error
                    }
                }
                lastError?.let { throw it }
            }
            completed += artifact.bytes
            onProgress(completed, downloadBytes)
        }
    }

    suspend fun importFromTree(treeUri: Uri, onProgress: (Long, Long) -> Unit): Result<Unit> = modelOperation {
        withImportReadPermission(treeUri) {
            val resolver = context.contentResolver
            val signal = CancellationSignal()
            val activeDescriptor = AtomicReference<AssetFileDescriptor?>()
            val activeInput = AtomicReference<InputStream?>()
            withAsrSafCancellation(cancel = {
                runCatching { signal.cancel() }
                runCatching { activeDescriptor.get()?.close() }
                runCatching { activeInput.get()?.close() }
            }) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                val documents = mutableMapOf<String, Uri>()
                resolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null, signal,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val name = cursor.getString(1)
                        if (artifacts.any { it.name == name }) {
                            documents[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                        }
                    }
                }
                if (!artifacts.all { it.name in documents }) {
                    throw AsrModelPreparationException(context.getString(R.string.podcast_asr_model_files_missing, artifacts.size))
                }
                var completed = 0L
                artifacts.forEach { artifact ->
                    currentCoroutineContext().ensureActive()
                    if (!validFile(directory.resolve(artifact.name), artifact)) {
                        checkNotNull(resolver.openAssetFileDescriptor(documents.getValue(artifact.name), "r", signal)).use { descriptor ->
                            activeDescriptor.set(descriptor)
                            try {
                                currentCoroutineContext().ensureActive()
                                descriptor.createInputStream().use { input ->
                                    activeInput.set(input)
                                    try {
                                        currentCoroutineContext().ensureActive()
                                        copyVerified(input, artifact) { onProgress(completed + it, downloadBytes) }
                                    } finally {
                                        activeInput.compareAndSet(input, null)
                                    }
                                }
                            } finally {
                                activeDescriptor.compareAndSet(descriptor, null)
                            }
                        }
                    }
                    completed += artifact.bytes
                    onProgress(completed, downloadBytes)
                }
            }
        }
    }

    private suspend fun <T> withImportReadPermission(treeUri: Uri, block: suspend () -> T): T {
        val resolver = context.contentResolver
        val hadReadPermission = runCatching {
            resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        }.getOrNull()
        // Persist only for this operation, so an Activity recreation cannot revoke an in-flight import.
        // Providers without persistable grants can still use the picker-provided temporary grant.
        val acquired = hadReadPermission == false && runCatching {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        try {
            return block()
        } finally {
            if (acquired) runCatching {
                resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private suspend fun modelOperation(block: suspend () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                check(directory.isDirectory || directory.mkdirs())
                block()
                validatedFingerprint = null
                if (!isReady()) throw AsrModelPreparationException(context.getString(R.string.podcast_subtitle_asr_model_corrupt))
                Result.success(Unit)
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                Result.failure(AsrModelPreparationException(context.getString(R.string.podcast_subtitle_asr_model_failed), error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Result.failure(localizedModelFailure(error, context.getString(R.string.podcast_subtitle_asr_model_failed)))
            }
        }
    }

    private suspend fun copyVerified(input: InputStream, artifact: AsrModelArtifact, onProgress: (Long) -> Unit) {
        val partial = directory.resolve("${artifact.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > artifact.bytes) throw AsrModelPreparationException(invalidFileMessage(artifact))
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress(written)
                }
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            if (written != artifact.bytes || digest.digest().toHex() != artifact.sha256) {
                throw AsrModelPreparationException(invalidFileMessage(artifact))
            }
            Files.move(partial.toPath(), directory.resolve(artifact.name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            validatedFingerprint = null
        } finally {
            partial.delete()
        }
    }

    private fun invalidFileMessage(artifact: AsrModelArtifact): String =
        context.getString(R.string.podcast_asr_model_file_invalid, artifact.name)

    private fun validFile(file: File, artifact: AsrModelArtifact): Boolean = runCatching {
        if (!file.isFile || file.length() != artifact.bytes) return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex() == artifact.sha256
    }.getOrDefault(false)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

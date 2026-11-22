/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.innertube.SharedHttpPool
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.BlockAiSlopArtistsKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

object AiSlopBlocklist {
    const val CONTRIBUTION_URL = "https://github.com/Override92/AiSList"
    private const val SOURCE_URL =
        "https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_blocklist.txt"
    internal const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    private const val CACHE_FILE_NAME = "aislist_blocklist.txt"

    private val channelIdPattern = Regex("UC[A-Za-z0-9_-]{20,24}")
    private val refreshMutex = Mutex()
    private val _entries = MutableStateFlow<Set<String>>(emptySet())
    val entries: StateFlow<Set<String>> = _entries.asStateFlow()

    @Volatile
    private var initialized = false

    suspend fun init(context: Context) {
        if (initialized) return
        withContext(Dispatchers.IO) {
            val loaded = readCacheFile(context.cacheDir)
            if (loaded.isNotEmpty()) {
                _entries.value = loaded
            }
            initialized = true
        }
    }

    suspend fun refresh(
        context: Context,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val enabled =
            runCatching { context.dataStore.data.first()[BlockAiSlopArtistsKey] ?: false }
                .getOrDefault(false)
        if (!enabled) return@withContext

        refreshMutex.withLock {
            val stillEnabled =
                runCatching { context.dataStore.data.first()[BlockAiSlopArtistsKey] ?: false }
                    .getOrDefault(false)
            if (!stillEnabled) return@withLock

            if (!initialized) {
                val loaded = readCacheFile(context.cacheDir)
                if (loaded.isNotEmpty()) {
                    _entries.value = loaded
                }
                initialized = true
            }

            val file = cacheFile(context.cacheDir)
            val now = System.currentTimeMillis()
            if (!force && _entries.value.isNotEmpty() && file.exists() && now - file.lastModified() < REFRESH_INTERVAL_MS) {
                return@withLock
            }

            try {
                val client =
                    SharedHttpPool.newClient(
                        proxy = YouTube.proxy,
                        proxyAuth = YouTube.proxyAuth,
                    ).newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                val request = Request.Builder().url(SOURCE_URL).get().build()
                val body =
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "AI blocklist request failed (${response.code})" }
                        checkDeclaredSize(response.header("Content-Length")?.toLongOrNull())
                        readBoundedText(response.body.byteStream())
                    }
                val parsed = parseDownloaded(body)
                writeCacheFile(context.cacheDir, body)
                _entries.value = parsed
                initialized = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AI blocklist refresh failed; keeping %d cached entries", _entries.value.size)
            }
        }
    }

    internal fun readBoundedText(input: java.io.InputStream): String {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            check(total <= MAX_RESPONSE_BYTES) {
                "AI blocklist size $total exceeds $MAX_RESPONSE_BYTES"
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    internal fun checkDeclaredSize(declaredSize: Long?) {
        check(declaredSize == null || declaredSize <= MAX_RESPONSE_BYTES) {
            "AI blocklist declared size $declaredSize exceeds $MAX_RESPONSE_BYTES"
        }
    }

    internal fun parseDownloaded(body: String): Set<String> {
        check(body.length <= MAX_RESPONSE_BYTES) {
            "AI blocklist size ${body.length} exceeds $MAX_RESPONSE_BYTES"
        }
        return parse(body).also { check(it.isNotEmpty()) { "AI blocklist contains no entries" } }
    }

    internal fun parse(text: String): Set<String> =
        text
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("@") || it.matches(channelIdPattern) }
            .map(String::lowercase)
            .toSet()

    fun matches(
        channelId: String?,
        name: String?,
        entries: Set<String> = _entries.value,
    ): Boolean {
        if (entries.isEmpty()) return false
        val normalizedId = channelId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        if (normalizedId != null && normalizedId in entries) return true
        val normalizedName = name?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: return false
        val handle = if (normalizedName.startsWith("@")) normalizedName else "@$normalizedName"
        return handle in entries
    }

    internal fun cacheFile(cacheDir: File): File = File(cacheDir, CACHE_FILE_NAME)

    internal fun writeCacheFile(
        cacheDir: File,
        body: String,
    ) {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val target = cacheFile(cacheDir)
        val tmp = File(cacheDir, "$CACHE_FILE_NAME.tmp")
        tmp.writeText(body, Charsets.UTF_8)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    internal fun readCacheFile(cacheDir: File): Set<String> {
        val file = cacheFile(cacheDir)
        if (!file.exists() || !file.isFile) return emptySet()
        return runCatching {
            if (file.length() > MAX_RESPONSE_BYTES) {
                emptySet()
            } else {
                parse(file.readText(Charsets.UTF_8))
            }
        }.getOrDefault(emptySet())
    }

    internal fun replaceForTest(values: Set<String>) {
        _entries.value = values.map { it.trim().lowercase() }.toSet()
        initialized = true
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class CrashReport(
    val id: String,
    val timestamp: Long,
    val appVersion: String,
    val deviceInfo: String,
    val stackTrace: String,
    val message: String? = null,
) {
    fun redacted(): CrashReport {
        val redactedTrace = redactDiagnosticText(stackTrace).take(MAX_TRACE_CHARS)
        val redactedMessage = message?.let(::redactDiagnosticText)?.take(MAX_MESSAGE_CHARS)
        return copy(
            appVersion = redactDiagnosticText(appVersion).take(MAX_HEADER_CHARS),
            deviceInfo = redactDiagnosticText(deviceInfo).take(MAX_HEADER_CHARS),
            stackTrace = redactedTrace,
            message = redactedMessage,
        )
    }

    fun formatForDisplay(): String =
        buildString {
            appendLine("MetroVerse Crash Report")
            appendLine("=".repeat(50))
            appendLine()
            appendLine(deviceInfo)
            appendLine("App version: $appVersion")
            message?.takeIf { it.isNotBlank() }?.let {
                appendLine("Message: $it")
            }
            appendLine()
            appendLine("=".repeat(50))
            appendLine("Stacktrace:")
            appendLine("=".repeat(50))
            appendLine()
            append(stackTrace)
        }

    companion object {
        private const val MAX_HEADER_CHARS = 512
        private const val MAX_MESSAGE_CHARS = 2_048
        private const val MAX_TRACE_CHARS = 200_000
        private val sequence = AtomicInteger(0)

        fun create(
            appVersion: String,
            deviceInfo: String,
            error: Throwable,
            timestampMs: Long = System.currentTimeMillis(),
        ): CrashReport {
            val id = "$timestampMs-${sequence.getAndIncrement()}"
            return CrashReport(
                id = id,
                timestamp = timestampMs,
                appVersion = appVersion,
                deviceInfo = deviceInfo,
                stackTrace = error.stackTraceToString(),
                message = error.message,
            ).redacted()
        }
    }
}

class FileCrashStore(
    private val directory: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(report: CrashReport) =
        locked {
            require(report.id.matches(Regex("[0-9]+-[0-9]+")))
            val destination = File(directory, "${report.id}.json")
            val temporary = File(directory, "${report.id}.tmp")
            val encodedBytes = encodeBoundedReport(report.redacted())
            try {
                temporary.outputStream().use { stream ->
                    stream.write(encodedBytes)
                    stream.flush()
                    stream.fd.sync()
                }
                moveReplacing(temporary, destination)
                reports().drop(MAX_REPORTS).forEach { File(directory, "${it.id}.json").delete() }

                val markerTmp = File(directory, "pending.tmp")
                markerTmp.outputStream().use { stream ->
                    stream.write(report.id.toByteArray())
                    stream.flush()
                    stream.fd.sync()
                }
                moveReplacing(markerTmp, File(directory, "pending"))
            } finally {
                temporary.delete()
                File(directory, "pending.tmp").delete()
            }
        }

    fun get(id: String): CrashReport? =
        locked {
            if (!id.matches(Regex("[0-9]+-[0-9]+"))) return@locked null
            val file = File(directory, "$id.json")
            if (!file.isFile) return@locked null
            runCatching { json.decodeFromString<CrashReport>(file.readText()).redacted() }.getOrNull()
        }

    fun list(): List<CrashReport> = locked { reports() }

    fun pendingReportId(): String? =
        locked {
            val marker = File(directory, "pending")
            if (!marker.isFile) null else marker.readText().trim().takeIf { id -> getUnsafe(id) != null }
        }

    fun acknowledge(id: String) =
        locked {
            val marker = File(directory, "pending")
            if (marker.isFile && marker.readText().trim() == id) {
                marker.delete()
            }
        }

    fun clear() =
        locked {
            directory
                .listFiles()
                .orEmpty()
                .filter { it.extension == "json" || it.extension == "tmp" || it.name == "pending" }
                .forEach { it.delete() }
        }

    private fun getUnsafe(id: String): CrashReport? {
        if (!id.matches(Regex("[0-9]+-[0-9]+"))) return null
        val file = File(directory, "$id.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<CrashReport>(file.readText()).redacted() }.getOrNull()
    }

    private fun reports(): List<CrashReport> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull {
                runCatching { json.decodeFromString<CrashReport>(it.readText()).redacted() }.getOrNull()
            }.sortedWith(compareByDescending<CrashReport> { it.timestamp }.thenByDescending { it.id })

    private fun encodeBoundedReport(initial: CrashReport): ByteArray {
        var current = initial
        var encoded = json.encodeToString(current).toByteArray(Charsets.UTF_8)
        while (encoded.size > MAX_REPORT_BYTES && current.stackTrace.isNotEmpty()) {
            val suffix = "\n...[truncated]"
            val available = (current.stackTrace.length / 2).coerceAtLeast(0)
            val trimmedTrace =
                if (available == 0) "" else current.stackTrace.take(available) + suffix
            current = current.copy(stackTrace = trimmedTrace)
            encoded = json.encodeToString(current).toByteArray(Charsets.UTF_8)
        }
        return encoded
    }

    private fun moveReplacing(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun <T> locked(block: () -> T): T =
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory, ".lock"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }

    companion object {
        private const val MAX_REPORTS = 50
        private const val MAX_REPORT_BYTES = 256 * 1024
        private val lock = Any()
    }
}

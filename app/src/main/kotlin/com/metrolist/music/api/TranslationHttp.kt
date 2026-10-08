/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitTranslationBody(): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use {
                    if (!continuation.isActive) return
                    if (!it.isSuccessful) {
                        throw TranslationRequestException(
                            httpStatusCode = it.code,
                            retryAfterMs = parseTranslationRetryAfter(it.header("Retry-After")),
                            message = "Translation request failed (HTTP ${it.code})",
                        )
                    }
                    // Keep cancellation attached until the body is consumed, not just until
                    // headers arrive. Some providers take much longer to send the body.
                    it.body.string()
                }
                continuation.resume(body)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

internal fun parseTranslationRetryAfter(
    value: String?,
    nowMs: Long = System.currentTimeMillis(),
): Long? {
    val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (header.all { it in '0'..'9' }) {
        val seconds = header.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
    }

    val retryAtMs = try {
        ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
        return null
    }
    if (retryAtMs <= nowMs) return 0L
    return try {
        Math.subtractExact(retryAtMs, nowMs)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

internal fun alignProviderTranslationLines(
    translatedLines: List<String>,
    expectedLineCount: Int,
    strictLineCount: Boolean,
): List<String> {
    if (strictLineCount && translatedLines.size != expectedLineCount) {
        throw TranslationRequestException(
            httpStatusCode = null,
            retryAfterMs = null,
            message = "Translation response line count mismatch",
        )
    }
    return when {
        translatedLines.size == expectedLineCount -> translatedLines
        translatedLines.size > expectedLineCount -> translatedLines.take(expectedLineCount)
        else -> translatedLines + List(expectedLineCount - translatedLines.size) { "" }
    }
}

internal fun Exception.asTranslationRequestException(): TranslationRequestException =
    this as? TranslationRequestException ?: TranslationRequestException(
        httpStatusCode = null,
        retryAfterMs = null,
        message = "Translation request failed",
        cause = this,
    )

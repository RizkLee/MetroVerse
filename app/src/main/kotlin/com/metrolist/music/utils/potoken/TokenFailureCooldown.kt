/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.potoken

import android.os.SystemClock

/** Access under the token provider's mutex. Cancellation is never recorded as a failure. */
internal class TokenFailureCooldown(
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val networkScope: () -> String = { "default" },
) {
    private data class Scope(
        val visitorData: String?,
        val cookie: String?,
        val network: String,
    )

    private var scope: Scope? = null
    private var untilMs = 0L
    private var backoffMs = INITIAL_BACKOFF_MS

    fun isCoolingDown(
        visitorData: String?,
        cookie: String?,
    ): Boolean {
        val current = Scope(visitorData, cookie, networkScope())
        val previous = scope
        if (previous == null || previous.cookie != cookie || previous.network != current.network ||
            (visitorData != null && previous.visitorData != null && previous.visitorData != visitorData)
        ) {
            reset()
        }
        scope = current.copy(visitorData = visitorData ?: scope?.visitorData)
        return elapsedMs() < untilMs
    }

    fun recordFailure(
        visitorData: String?,
        cookie: String?,
    ) {
        if (scope?.network?.let { it != networkScope() } == true) {
            reset()
            return
        }
        isCoolingDown(visitorData, cookie)
        untilMs = elapsedMs() + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun reset() {
        scope = null
        untilMs = 0L
        backoffMs = INITIAL_BACKOFF_MS
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 120_000L
    }
}

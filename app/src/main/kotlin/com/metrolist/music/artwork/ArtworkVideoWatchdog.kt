/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.artwork

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

class ArtworkVideoWatchdog {
    private var lastFrame = 0L
    private var waitingSinceMs: Long? = null

    fun reset() {
        waitingSinceMs = null
    }

    suspend fun observe(
        active: Flow<Boolean>,
        timeMs: () -> Long,
        renderedFrames: () -> Long,
        onStalled: () -> Unit,
    ) {
        active.distinctUntilChanged().collectLatest { expectingFrames ->
            reset()
            if (!expectingFrames) return@collectLatest
            while (true) {
                if (isStalled(timeMs(), renderedFrames(), expectingFrames = true)) {
                    onStalled()
                    break
                }
                delay(1_000L)
            }
        }
    }

    fun isStalled(
        nowMs: Long,
        renderedFrames: Long,
        expectingFrames: Boolean,
    ): Boolean {
        if (!expectingFrames || renderedFrames != lastFrame) {
            lastFrame = renderedFrames
            waitingSinceMs = if (expectingFrames) nowMs else null
            return false
        }
        val since = waitingSinceMs ?: nowMs.also { waitingSinceMs = it }
        return nowMs - since >= STALL_TIMEOUT_MS
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 15_000L
    }
}

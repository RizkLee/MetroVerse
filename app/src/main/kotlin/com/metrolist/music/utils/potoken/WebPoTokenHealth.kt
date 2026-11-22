/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.potoken

/**
 * Remembers that YouTube recently rejected web PO tokens (403 on a tokenized URL).
 * While active, playback can prefer the tokenless VISIONOS 0.1 client, which keeps playing without a token.
 */
object WebPoTokenHealth {
    const val TOKENLESS_AUDIO_CLIENT = "VISIONOS_0_1"
    const val TOKENLESS_AUDIO_PROFILE = "VISIONOS_0_1__nopo"
    private const val REJECTION_TTL_MS = 30 * 60_000L

    @Volatile
    private var rejectedUntilMs = 0L

    fun recordRejection(nowMs: Long = System.currentTimeMillis()) {
        rejectedUntilMs = nowMs + REJECTION_TTL_MS
    }

    fun tokensRejected(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < rejectedUntilMs

    internal fun reset() {
        rejectedUntilMs = 0L
    }
}

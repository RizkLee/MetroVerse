/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/** A one-byte range probe's Content-Length is one byte, not the recording length. */
internal fun resolvedAudioContentLength(status: Int, contentRange: String?, contentLength: String?): Long? {
    if (status == 206) {
        val match = Regex("bytes\\s+0-0/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(contentRange?.trim().orEmpty())
        return match?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L }
    }
    return contentLength?.toLongOrNull()?.takeIf { status == 200 && it > 0L }
}

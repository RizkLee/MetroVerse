package com.metrolist.music.cache

import java.security.MessageDigest

internal fun lyricsCacheFingerprint(lyrics: String): String =
    MessageDigest.getInstance("SHA-256").digest(lyrics.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun hasVerifiedCacheOrigin(lyrics: String, expectedHash: String?): Boolean =
    expectedHash != null && expectedHash == lyricsCacheFingerprint(lyrics)

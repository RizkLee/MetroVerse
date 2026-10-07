package com.metrolist.music.db

/** A query projection only; it does not add a Room table or change the schema. */
data class LyricsCacheEntry(
    val id: String,
    val bytes: Long,
    val provider: String,
    val hasSong: Boolean,
    val inLibrary: Boolean,
    val liked: Boolean,
    val downloaded: Boolean,
    val lastPlayedMs: Long,
)

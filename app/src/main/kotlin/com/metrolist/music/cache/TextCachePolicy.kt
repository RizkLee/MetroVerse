package com.metrolist.music.cache

enum class TextCacheCategory { PODCAST, LYRICS }

enum class TextCacheClearTarget { ALL, PODCASTS, LYRICS }

data class TextCacheEntry(
    val id: String,
    val category: TextCacheCategory,
    val bytes: Long,
    val lastAccessMs: Long,
    val priority: Int = 0,
    val protected: Boolean = false,
    val unverified: Boolean = false,
)

internal fun textCacheRemovalOrder(entries: List<TextCacheEntry>): List<TextCacheEntry> =
    entries.filterNot { it.protected || it.unverified }.sortedWith(
        compareBy<TextCacheEntry> { it.category.ordinal }
            .thenBy { it.priority }
            .thenBy { it.lastAccessMs }
            .thenBy { it.id },
    )

internal fun textCacheEvictions(entries: List<TextCacheEntry>, limitBytes: Long): List<TextCacheEntry> {
    require(limitBytes >= 0)
    var remaining = entries.sumOf { it.bytes.coerceAtLeast(0L) }
    return textCacheRemovalOrder(entries).takeWhile { entry ->
        (remaining > limitBytes).also { if (it) remaining -= entry.bytes.coerceAtLeast(0L) }
    }
}

internal fun lyricCachePriority(hasSong: Boolean, inLibrary: Boolean, liked: Boolean, downloaded: Boolean): Int =
    when {
        !hasSong -> 0
        downloaded -> 3
        inLibrary || liked -> 2
        else -> 1
    }

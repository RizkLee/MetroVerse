package com.metrolist.music.lyrics

/** A small process cache; persistent translations remain owned by Room. */
internal class TranslationMemoryCache(
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val maxEntries: Int = 16,
) {
    private val entries = LinkedHashMap<String, List<String>>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    operator fun get(key: String): List<String>? = entries[key]

    @Synchronized
    operator fun set(key: String, value: List<String>) {
        entries.remove(key)?.let { bytes -= sizeOf(key, it) }
        val size = sizeOf(key, value)
        if (size > maxBytes) return
        entries[key] = value.toList()
        bytes += size
        val iterator = entries.entries.iterator()
        while ((bytes > maxBytes || entries.size > maxEntries) && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= sizeOf(entry.key, entry.value)
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    private fun sizeOf(key: String, lines: List<String>): Long =
        key.length.toLong() * 2 + lines.sumOf { it.length.toLong() * 2 }
}

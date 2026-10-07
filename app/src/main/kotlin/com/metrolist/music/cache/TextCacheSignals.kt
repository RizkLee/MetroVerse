package com.metrolist.music.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Shares cache invalidation and active-media protection without repository dependency cycles. */
object TextCacheSignals {
    private val owners = mutableMapOf<String, String>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun notifyChanged() = revision.update { it + 1 }

    fun protect(owner: String, mediaId: String?) {
        val changed = synchronized(owners) {
            val key = mediaId?.takeLast(48)
            if (owners[owner] == key) false else {
                if (key == null) owners.remove(owner) else owners[owner] = key
                true
            }
        }
        if (changed) notifyChanged()
    }

    fun isProtected(mediaId: String): Boolean = synchronized(owners) {
        mediaId.takeLast(48) in owners.values
    }
}

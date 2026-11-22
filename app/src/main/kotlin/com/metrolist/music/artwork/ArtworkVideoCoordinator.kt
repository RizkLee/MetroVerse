/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.artwork

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ArtworkVideoPriority(
    val rank: Int,
) {
    ARTIST(1),
    PLAYER(2),
}

/**
 * Coordinates hardware video decoder ownership across player covers and artist profile banners
 * so simultaneous surfaces never compete for scarce MediaCodec instances.
 */
object ArtworkVideoCoordinator {
    private data class Claim(
        val ownerId: String,
        val priority: ArtworkVideoPriority,
        val sequence: Long,
    )

    private val lock = Any()
    private val claims = mutableMapOf<String, Claim>()
    private var nextSequence = 0L

    private val _activeOwnerId = MutableStateFlow<String?>(null)
    val activeOwnerId: StateFlow<String?> = _activeOwnerId.asStateFlow()

    fun acquire(
        ownerId: String,
        priority: ArtworkVideoPriority,
    ) {
        synchronized(lock) {
            claims[ownerId] = Claim(ownerId = ownerId, priority = priority, sequence = ++nextSequence)
            recomputeLocked()
        }
    }

    fun release(ownerId: String) {
        synchronized(lock) {
            if (claims.remove(ownerId) != null) {
                recomputeLocked()
            }
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            claims.clear()
            nextSequence = 0L
            _activeOwnerId.value = null
        }
    }

    private fun recomputeLocked() {
        val winner =
            claims.values.maxWithOrNull(
                compareBy<Claim> { it.priority.rank }.thenBy { it.sequence },
            )
        _activeOwnerId.value = winner?.ownerId
    }
}

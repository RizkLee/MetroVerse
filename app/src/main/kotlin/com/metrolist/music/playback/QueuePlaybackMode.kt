/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.playback

/** The stop mode belongs to the active queue, never to saved autoplay preferences. */
enum class QueuePlaybackMode {
    SEQUENTIAL,
    SHUFFLE,
    STOP_AFTER_EACH;

    fun next(allowStopAfterEach: Boolean = false): QueuePlaybackMode = when (this) {
        SEQUENTIAL -> SHUFFLE
        SHUFFLE -> if (allowStopAfterEach) STOP_AFTER_EACH else SEQUENTIAL
        STOP_AFTER_EACH -> SEQUENTIAL
    }

    companion object {
        fun resolve(
            shuffleEnabled: Boolean,
            stopAfterEach: Boolean,
            allowStopAfterEach: Boolean = false,
        ): QueuePlaybackMode = when {
            stopAfterEach && allowStopAfterEach -> STOP_AFTER_EACH
            shuffleEnabled -> SHUFFLE
            else -> SEQUENTIAL
        }
    }
}

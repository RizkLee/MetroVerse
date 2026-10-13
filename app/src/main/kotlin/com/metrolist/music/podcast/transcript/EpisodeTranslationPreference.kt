/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A player-menu choice expires with its episode; the settings default stays independent. */
internal class EpisodeTranslationPreference {
    private var episodeId: String? = null
    private val _override = MutableStateFlow<Boolean?>(null)
    val override: StateFlow<Boolean?> = _override.asStateFlow()

    fun onEpisodeChanged(id: String?) {
        if (episodeId == id) return
        episodeId = id
        _override.value = null
    }

    fun setEnabled(enabled: Boolean) {
        if (episodeId != null) _override.value = enabled
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import java.util.IdentityHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Includes accepted tasks still waiting for an older task to release its native model. */
internal class AsrModelUseTracker {
    private val owners = IdentityHashMap<Job, AsrModel>()
    private val _models = MutableStateFlow<Set<AsrModel>>(emptySet())
    val models = _models.asStateFlow()

    fun track(owner: Job, model: AsrModel) {
        synchronized(owners) {
            owners[owner] = model
            _models.value = owners.values.toSet()
        }
        owner.invokeOnCompletion {
            synchronized(owners) {
                owners.remove(owner)
                _models.value = owners.values.toSet()
            }
        }
    }
}

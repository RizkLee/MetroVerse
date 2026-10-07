/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class SubtitleAutoScrollController(
    private val scope: CoroutineScope,
    private val waitBeforeResuming: suspend () -> Unit = { delay(6_000) },
) {
    private val _following = MutableStateFlow(true)
    val following = _following.asStateFlow()
    private var resumeJob: Job? = null

    fun pause() {
        resumeJob?.cancel()
        resumeJob = null
        _following.value = false
    }

    fun resume() {
        resumeJob?.cancel()
        resumeJob = null
        _following.value = true
    }

    fun resumeAfterDrag() {
        resumeJob?.cancel()
        // Waiting inside the drag collector would delay later drags and replay stale pauses after a seek.
        resumeJob = scope.launch {
            waitBeforeResuming()
            _following.value = true
        }
    }
}

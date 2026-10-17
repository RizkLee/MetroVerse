/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A repeat restarts at zero; a pending resume from an older occurrence must not seek it. */
internal class EpisodePositionRestorer(
    private val scope: CoroutineScope,
    private val loadPosition: suspend (String) -> Long?,
    private val currentEpisodeId: () -> String?,
    private val seekTo: (Long) -> Unit,
) {
    private var job: Job? = null
    private var generation = 0L

    fun onTransition(episodeId: String?, reason: Int) {
        val request = ++generation
        job?.cancel()
        job = null
        if (episodeId == null || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        job = scope.launch {
            delay(100)
            val saved = try {
                withContext(Dispatchers.IO) { loadPosition(episodeId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            if (request == generation && currentEpisodeId() == episodeId && saved != null && saved > 0) {
                seekTo(saved)
            }
        }
    }
}

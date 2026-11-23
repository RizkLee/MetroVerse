/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import androidx.media3.common.C
import com.metrolist.lastfm.LastFM
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 50,
    private val isLastFmReady: () -> Boolean = {
        LastFM.isInitialized() && !LastFM.sessionKey.isNullOrBlank()
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onScrobble: suspend (
        artist: String,
        track: String,
        timestamp: Long,
        album: String?,
        duration: Int?,
    ) -> Result<Unit> = { artist, track, timestamp, album, duration ->
        LastFM.scrobble(
            artist = artist,
            track = track,
            timestamp = timestamp,
            album = album,
            duration = duration,
        ).map { Unit }
    },
    private val onNowPlaying: suspend (
        artist: String,
        track: String,
        album: String?,
        duration: Int?,
    ) -> Result<Unit> = { artist, track, album, duration ->
        LastFM.updateNowPlaying(
            artist = artist,
            track = track,
            album = album,
            duration = duration,
        ).map { Unit }
    },
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    private var currentTrackId: String? = null
    var useNowPlaying = true

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
        currentTrackId = null
    }

    fun onSongStart(metadata: MediaMetadata?, duration: Long? = null) {
        if (metadata == null || metadata.isEpisode) {
            onSongStop()
            return
        }
        if (!isLastFmReady()) return
        val durationSec = effectiveDurationSeconds(metadata, duration)
        if (!shouldScrobbleTrack(metadata, durationSec, minSongDuration)) {
            onSongStop()
            return
        }
        songStartedAt = nowMs() / 1000L
        songStarted = true
        currentTrackId = metadata.id
        startScrobbleTimer(metadata, durationSec)
        if (useNowPlaying) {
            updateNowPlaying(metadata, durationSec)
        }
    }

    fun onSongResume(metadata: MediaMetadata) {
        if (metadata.isEpisode || !isLastFmReady()) {
            onSongStop()
            return
        }
        resumeScrobbleTimer(metadata)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
        currentTrackId = null
    }

    private fun startScrobbleTimer(metadata: MediaMetadata, durationSec: Int) {
        scrobbleJob?.cancel()
        val threshold = durationSec * 1000L * scrobbleDelayPercent
        scrobbleRemainingMillis = min(threshold.toLong(), scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0L) {
            scrobbleSong(metadata, durationSec)
            return
        }
        scrobbleTimerStartedAt = nowMs()
        scrobbleJob = scope.launch {
            delay(scrobbleRemainingMillis)
            scrobbleRemainingMillis = 0L
            scrobbleSong(metadata, durationSec)
            scrobbleJob = null
        }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = (nowMs() - scrobbleTimerStartedAt).coerceAtLeast(0L)
            scrobbleRemainingMillis = (scrobbleRemainingMillis - elapsed).coerceAtLeast(0L)
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(metadata: MediaMetadata) {
        if (scrobbleRemainingMillis <= 0L) return
        val durationSec = metadata.duration
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = nowMs()
        scrobbleJob = scope.launch {
            delay(scrobbleRemainingMillis)
            scrobbleRemainingMillis = 0L
            scrobbleSong(metadata, durationSec)
            scrobbleJob = null
        }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
    }

    private fun scrobbleSong(metadata: MediaMetadata, durationSec: Int) {
        if (!isLastFmReady() || metadata.isEpisode) return
        val artistName = metadata.artists.joinToString { it.name }.trim()
        if (artistName.isBlank() || metadata.title.isBlank()) return
        scope.launch {
            onScrobble(
                artistName,
                metadata.title,
                songStartedAt,
                metadata.album?.title,
                durationSec.takeIf { it > 0 },
            ).onFailure { error ->
                Timber.tag(TAG).w(error, "Last.fm scrobble failed for %s", metadata.id)
            }
        }
    }

    private fun updateNowPlaying(metadata: MediaMetadata, durationSec: Int) {
        if (!isLastFmReady() || metadata.isEpisode) return
        val artistName = metadata.artists.joinToString { it.name }.trim()
        if (artistName.isBlank() || metadata.title.isBlank()) return
        scope.launch {
            onNowPlaying(
                artistName,
                metadata.title,
                metadata.album?.title,
                durationSec.takeIf { it > 0 },
            ).onFailure { error ->
                Timber.tag(TAG).w(error, "Last.fm now playing failed for %s", metadata.id)
            }
        }
    }

    fun onPlayerStateChanged(isPlaying: Boolean, metadata: MediaMetadata?, duration: Long? = null) {
        if (metadata == null || metadata.isEpisode) {
            onSongStop()
            return
        }
        if (currentTrackId != null && currentTrackId != metadata.id) {
            onSongStop()
        }
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(metadata, duration)
            } else {
                onSongResume(metadata)
            }
        } else {
            onSongPause()
        }
    }

    companion object {
        private const val TAG = "ScrobbleManager"

        internal fun effectiveDurationSeconds(
            metadata: MediaMetadata,
            durationMs: Long?,
        ): Int {
            val resolvedFromPlayer =
                durationMs
                    ?.takeIf { it > 0L && it != C.TIME_UNSET }
                    ?.div(1000L)
                    ?.toInt()
            return resolvedFromPlayer ?: metadata.duration.coerceAtLeast(0)
        }

        internal fun shouldScrobbleTrack(
            metadata: MediaMetadata?,
            durationSec: Int,
            minSongDuration: Int,
        ): Boolean {
            if (metadata == null || metadata.isEpisode) return false
            if (metadata.title.isBlank()) return false
            if (metadata.artists.none { it.name.isNotBlank() }) return false
            return durationSec > minSongDuration
        }
    }
}

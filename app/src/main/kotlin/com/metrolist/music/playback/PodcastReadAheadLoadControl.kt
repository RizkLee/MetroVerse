/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import com.metrolist.music.extensions.metadata

internal class PodcastReadAheadLoadControl(
    private val delegate: LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(50_000, 50_000, 750, 2_000)
        .build(),
) : LoadControl by delegate {
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    // Kotlin delegation does not forward Java default methods; Media3's lifecycle must reach the delegate.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (parameters.playerId != PlayerId.PRELOAD &&
            parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid) == C.INDEX_UNSET
        ) return false
        val defaultDecision = delegate.shouldContinueLoading(parameters)
        if (defaultDecision || !canReadAhead(parameters)) return defaultDecision

        // Keep the existing audio memory budget: a long episode must not fill the heap.
        return delegate.getAllocator(parameters.playerId).totalBytesAllocated < DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
    }

    private fun canReadAhead(parameters: LoadControl.Parameters): Boolean {
        // A negative position belongs to a future queue item, not the episode being heard.
        if (!parameters.playWhenReady || parameters.playbackPositionUs < 0 || parameters.mediaPeriodId.isAd) return false
        val timeline = parameters.timeline
        val periodIndex = timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) return false
        timeline.getPeriod(periodIndex, period)
        timeline.getWindow(period.windowIndex, window)
        if (window.isLive || window.isDynamic) return false
        val metadata = window.mediaItem.metadata ?: return false
        return metadata.isEpisode && !metadata.mediaUrl.isNullOrBlank()
    }
}

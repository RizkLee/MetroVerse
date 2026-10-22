/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PodcastReadAheadLoadControlTest {
    private val playerId = PlayerId.UNSET
    private val periodUid = Any()
    private val control = PodcastReadAheadLoadControl().also { it.onPrepared(playerId) }

    @After
    fun tearDown() {
        control.onReleased(playerId)
    }

    @Test
    fun currentRssEpisodeContinuesReadingBeyondFiftySeconds() {
        assertTrue(control.shouldContinueLoading(parameters(bufferedMs = 50_000)))
        assertTrue(control.shouldContinueLoading(parameters(bufferedMs = 900_000)))
    }

    @Test
    fun readAheadStopsAtTheExistingAudioByteBudgetAndResumesAsItIsConsumed() {
        val allocator = control.getAllocator(playerId)
        val allocations = MutableList(DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE / allocator.individualAllocationLength) {
            allocator.allocate()
        }
        try {
            assertFalse(control.shouldContinueLoading(parameters(bufferedMs = 900_000)))
            allocator.release(allocations.removeLast())
            assertTrue(control.shouldContinueLoading(parameters(bufferedMs = 899_000)))
        } finally {
            allocations.forEach(allocator::release)
        }
    }

    @Test
    fun musicKeepsItsExistingTimeThreshold() {
        val music = mediaItem(isEpisode = false, mediaUrl = null)
        assertTrue(control.shouldContinueLoading(parameters(music, bufferedMs = 49_000)))
        assertFalse(control.shouldContinueLoading(parameters(music, bufferedMs = 50_000)))
    }

    @Test
    fun youtubeEpisodesKeepTheirExistingTimeThreshold() {
        assertFalse(control.shouldContinueLoading(parameters(mediaItem(mediaUrl = null))))
    }

    @Test
    fun pausedEpisodesOnlyUseOrdinaryBuffering() {
        assertTrue(control.shouldContinueLoading(parameters(bufferedMs = 10_000, playWhenReady = false)))
        assertFalse(control.shouldContinueLoading(parameters(playWhenReady = false)))
    }

    @Test
    fun futureQueueEpisodesAreNotReadAhead() {
        assertFalse(control.shouldContinueLoading(parameters(playbackPositionUs = -1)))
    }

    @Test
    fun liveAndDynamicWindowsAreNotReadAhead() {
        assertFalse(control.shouldContinueLoading(parameters(live = true)))
        assertFalse(control.shouldContinueLoading(parameters(dynamic = true)))
    }

    @Test
    fun emptyAndChangedTimelinesDoNotTriggerReadAhead() {
        assertFalse(control.shouldContinueLoading(parameters(timeline = Timeline.EMPTY)))
        assertFalse(control.shouldContinueLoading(parameters(periodId = MediaPeriodId(Any()))))
    }

    @Test
    fun adPeriodsAreNotReadAhead() {
        assertFalse(control.shouldContinueLoading(parameters(periodId = MediaPeriodId(periodUid, 0, 0, 0))))
    }

    @Test
    fun untaggedMediaIsNotTreatedAsAnRssEpisode() {
        assertFalse(control.shouldContinueLoading(parameters(MediaItem.fromUri("https://example.com/audio.mp3"))))
    }

    @Test
    fun startupAndRebufferingThresholdsAreUnchanged() {
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 749)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 750)))
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 1_999, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 2_000, rebuffering = true)))
    }

    private fun mediaItem(isEpisode: Boolean = true, mediaUrl: String? = "https://example.com/audio.mp3") =
        MediaMetadata(
            id = "test-episode",
            title = "Test",
            artists = emptyList(),
            duration = 3_600,
            isEpisode = isEpisode,
            mediaUrl = mediaUrl,
        ).toMediaItem()

    private fun parameters(
        item: MediaItem = mediaItem(),
        bufferedMs: Long = 60_000,
        playWhenReady: Boolean = true,
        playbackPositionUs: Long = 0,
        rebuffering: Boolean = false,
        live: Boolean = false,
        dynamic: Boolean = false,
        timeline: Timeline = EpisodeTimeline(item, live, dynamic),
        periodId: MediaPeriodId = MediaPeriodId(periodUid),
    ) = LoadControl.Parameters(
        playerId,
        timeline,
        periodId,
        playbackPositionUs,
        bufferedMs * 1_000,
        1f,
        playWhenReady,
        rebuffering,
        C.TIME_UNSET,
        C.TIME_UNSET,
    )

    private inner class EpisodeTimeline(
        private val item: MediaItem,
        private val live: Boolean,
        private val dynamic: Boolean,
    ) : Timeline() {
        override fun getWindowCount() = 1

        override fun getWindow(index: Int, window: Window, defaultPositionProjectionUs: Long): Window = window.apply {
            mediaItem = item
            isDynamic = dynamic
            liveConfiguration = if (live) MediaItem.LiveConfiguration.Builder().build() else null
        }

        override fun getPeriodCount() = 1

        override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period =
            period.set(null, periodUid, 0, 3_600_000_000, 0)

        override fun getIndexOfPeriod(uid: Any) = if (uid == periodUid) 0 else C.INDEX_UNSET

        override fun getUidOfPeriod(index: Int): Any = periodUid
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.metrolist.music.podcast.transcript.PodcastTranslationStatus
import com.metrolist.music.podcast.transcript.SubtitleCue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class PodcastSubtitleViewTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var listState: LazyListState
    private val cues = mutableStateOf((30..90).map(::cue))
    private val showTranslation = mutableStateOf(false)
    private var positionMs = 50_000L
    private var currentIndex = -1

    @After
    fun tearDown() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    @Test
    fun backfillingEarlierCuesKeepsThePlayingLineAnchoredInEveryFrame() {
        showSubtitles()
        val anchor = viewport()

        for (second in 29 downTo 20) {
            compose.runOnUiThread { cues.value = listOf(cue(second)) + cues.value }
            repeat(12) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals("Backfill at $second, frame $it", anchor, viewport())
            }
            compose.runOnIdle { assertEquals(cues.value.indexOf(cue(50)), currentIndex) }
        }
    }

    @Test
    fun backfillDuringPlaybackScrollDoesNotAnimateTowardsAnObsoleteIndex() {
        showSubtitles()
        compose.runOnUiThread { positionMs = 75_000L }
        compose.mainClock.advanceTimeBy(272)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Playback should still scroll smoothly", listState.isScrollInProgress) }

        compose.runOnUiThread { cues.value = (0..29).map(::cue) + cues.value }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val target = viewport()
        assertEquals(keyFor(75), target.first)
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals(target, viewport())
        }
    }

    @Test
    fun tappedCueRemainsTheScrollTargetWhenBackfillArrivesBeforeSeekAcknowledgement() {
        showSubtitles()
        compose.onNodeWithText("Line 52").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val anchor = viewport()
        assertEquals(keyFor(52), anchor.first)

        compose.runOnUiThread { cues.value = (0..29).map(::cue) + cues.value }
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals(anchor, viewport())
        }
    }

    @Test
    fun backfillPreservesManualBrowsingIncludingItsPixelOffset() {
        showSubtitles()
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.runOnUiThread { listState.requestScrollToItem(10, 17) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val anchor = viewport()
        assertEquals(keyFor(40), anchor.first)

        compose.runOnUiThread { cues.value = (0..29).map(::cue) + cues.value }
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals(anchor, viewport())
        }
    }

    @Test
    fun translationHintInsertionDoesNotMoveThePlayingLine() {
        showSubtitles()
        val anchor = viewport()
        compose.runOnUiThread { showTranslation.value = true }
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals(anchor, viewport())
        }
    }

    private fun showSubtitles() {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme {
                    listState = rememberLazyListState()
                    SubtitleCueList(
                        cues = cues.value,
                        translations = emptyMap(),
                        positionProvider = { positionMs },
                        offsetMs = 0,
                        showTranslation = showTranslation.value,
                        translationMissingCredentials = showTranslation.value,
                        textColor = Color.White,
                        translationStatus = PodcastTranslationStatus.Idle,
                        onSeek = {},
                        onCurrentCueChanged = { currentIndex = it },
                        onShareCues = {},
                        onRetryTranslation = {},
                        resyncToken = 0,
                        modifier = Modifier.width(360.dp).height(640.dp),
                        listState = listState,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertEquals(keyFor(50), viewport().first)
    }

    private fun viewport(): Pair<Any, Int> = compose.runOnIdle {
        val first = listState.layoutInfo.visibleItemsInfo.first { it.index == listState.firstVisibleItemIndex }
        first.key to first.offset
    }

    private fun keyFor(second: Int): String = SubtitleCueIdentityCache().keyedCues(listOf(cue(second))).single().key

    private fun cue(second: Int) = SubtitleCue(second * 1_000L, (second + 1) * 1_000L, "Line $second")
}

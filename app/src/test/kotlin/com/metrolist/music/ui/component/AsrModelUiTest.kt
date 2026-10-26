/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.metrolist.music.LocalPodcastSubtitleManager
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.AsrLanguage
import com.metrolist.music.podcast.transcript.AsrModelVariant
import com.metrolist.music.ui.screens.settings.PodcastSubtitleSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class AsrModelUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val tiny = AsrModelVariant.TINY_STREAMING
    private val small = AsrModelVariant.SMALL_STREAMING

    @After
    fun tearDown() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    @Test
    fun initialDownloadLetsTheUserChooseTheActualModel() {
        var chosen: AsrModelVariant? = null
        show {
            AsrModelDownloadDialog(45_233_659, AsrLanguage.ENGLISH, onDismiss = {}, onDownload = { chosen = it }, initialVariant = tiny)
        }
        compose.onNodeWithText("Small").performClick()
        compose.onNodeWithText("Model information: Moonshine Small Streaming — English").assertExists()
        compose.onNodeWithText("Tiny").performClick()
        compose.onNodeWithText(activity.get().getString(R.string.action_download)).performClick()
        compose.runOnIdle { assertEquals(tiny, chosen) }
    }

    @Test
    fun managementSelectsTinyAndProtectsTheActiveSmallModelFromDeletion() {
        var chosen: AsrModelVariant? = null
        val language = AsrLanguage.ENGLISH
        val models = setOf(language.model(tiny), language.model(small))
        show {
            AsrModelManagementDialog(language, small, models, emptyMap(), setOf(language.model(small)),
                onDismiss = {}, onUse = { chosen = it }, onDownload = {}, onDelete = {})
        }
        val delete = activity.get().getString(R.string.podcast_subtitle_delete_model)
        compose.onNodeWithText(delete).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Tiny").performScrollTo().performClick()
        compose.onNodeWithText(delete).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(activity.get().getString(R.string.podcast_asr_use_model)).performClick()
        compose.runOnIdle { assertEquals(tiny, chosen) }
    }

    @Test
    fun tinyOnlyLanguagesNeverOfferAnUnsupportedSmallModel() {
        var chosen: AsrModelVariant? = null
        show {
            AsrModelManagementDialog(AsrLanguage.MANDARIN, tiny, emptySet(), emptyMap(), emptySet(),
                onDismiss = {}, onUse = {}, onDownload = { chosen = it }, onDelete = {})
        }
        compose.onNodeWithText("Small").assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.action_download)).performClick()
        compose.runOnIdle { assertEquals(tiny, chosen) }
    }

    @Test
    fun modelPreparationShowsProgressAndDisablesSelectionUntilItFinishes() {
        show {
            AsrModelManagementDialog(AsrLanguage.ENGLISH, tiny, emptySet(), emptyMap(), emptySet(),
                onDismiss = {}, onUse = {}, onDownload = {}, onDelete = {}, busy = true, downloadProgress = 50L to 100L)
        }
        compose.onNodeWithText("Tiny").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(activity.get().getString(R.string.action_download)).assertIsNotEnabled()
        compose.onNodeWithText(activity.get().getString(R.string.podcast_subtitle_asr_model_downloading, 50)).performScrollTo().assertExists()
    }

    @Test
    fun settingsContainsExactlyOneModelEntryForEachLanguage() {
        show {
            CompositionLocalProvider(LocalPodcastSubtitleManager provides null) { PodcastSubtitleSettings() }
        }
        listOf("English", "German", "Spanish", "Japanese", "Mandarin Chinese", "Arabic", "Tagalog", "Vietnamese").forEach { name ->
            compose.onAllNodesWithText(activity.get().getString(R.string.podcast_asr_language_model, name)).assertCountEquals(1)
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent { MaterialTheme { content() } }
        }
        compose.waitForIdle()
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.AsrExitKind
import com.metrolist.music.podcast.transcript.AsrExitNotice
import com.metrolist.music.podcast.transcript.AsrLanguage
import com.metrolist.music.podcast.transcript.AsrModelVariant
import java.util.Locale
import org.junit.After
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
@Config(application = Application::class, sdk = [33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class AsrExitNoticeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val small = AsrLanguage.ENGLISH.model(AsrModelVariant.SMALL_STREAMING)

    @After
    fun tearDown() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    @Test
    fun confirmedMemoryPressureOffersTheTinyChooser() {
        var chosen = false
        show { AsrExitNoticeDialog(AsrExitNotice("attempt", small, AsrExitKind.MEMORY), {}, { chosen = true }) }
        compose.onNodeWithText(activity.get().getString(R.string.podcast_asr_exit_memory)).assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.podcast_asr_choose_tiny)).performClick()
        compose.runOnIdle { assertTrue(chosen) }
    }

    @Test
    fun alreadyTinyShowsTheRelevantExplanationWithoutAnotherTinyRecommendation() {
        var dismissed = false
        val model = AsrLanguage.ENGLISH.model(AsrModelVariant.TINY_STREAMING)
        show { AsrExitNoticeDialog(AsrExitNotice("attempt", model, AsrExitKind.MEMORY), { dismissed = true }, {}) }
        compose.onNodeWithText(activity.get().getString(R.string.podcast_asr_exit_memory_tiny)).assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.podcast_asr_choose_tiny)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.close)).performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun nonMemoryReasonsShowTheirOwnExplanationAndNeverOfferTheMemoryAction() {
        var kind by mutableStateOf(AsrExitKind.UNKNOWN)
        show { AsrExitNoticeDialog(AsrExitNotice("attempt", small, kind), {}, {}) }
        mapOf(AsrExitKind.UNKNOWN to R.string.podcast_asr_exit_unknown,
            AsrExitKind.NATIVE_CRASH to R.string.podcast_asr_exit_native,
            AsrExitKind.CRASH to R.string.podcast_asr_exit_crash,
            AsrExitKind.NOT_RESPONDING to R.string.podcast_asr_exit_not_responding).forEach { (reason, message) ->
            compose.runOnIdle { kind = reason }
            compose.onNodeWithText(activity.get().getString(message)).assertExists()
            compose.onNodeWithText(activity.get().getString(R.string.podcast_asr_choose_tiny)).assertDoesNotExist()
        }
    }

    @Test
    fun chineseLocaleResolvesTheActualLocalizedTitleAndBody() {
        show {
            val config = Configuration(activity.get().resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            val resources = activity.get().createConfigurationContext(config).resources
            CompositionLocalProvider(LocalConfiguration provides config, LocalResources provides resources) {
                AsrExitNoticeDialog(AsrExitNotice("attempt", AsrLanguage.MANDARIN.model(), AsrExitKind.MEMORY), {}, {})
            }
        }
        compose.onNodeWithText("上次普通话字幕识别中断").assertExists()
        compose.onNodeWithText("上次识别字幕时，系统因内存压力结束了应用。当时已使用 Tiny 模型，可稍后重试或暂时停止端侧识别。").assertExists()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent { MaterialTheme { content() } }
        }
        compose.waitForIdle()
    }
}

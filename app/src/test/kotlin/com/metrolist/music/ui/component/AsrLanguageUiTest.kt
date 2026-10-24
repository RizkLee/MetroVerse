/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.metrolist.music.podcast.transcript.AsrLanguage
import com.metrolist.music.podcast.transcript.AsrLanguageSelection
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class AsrLanguageUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @After
    fun tearDown() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    @Test
    fun languageDialogCanSelectTagalogAndSaveItAsTheChannelDefault() {
        var saved: Pair<AsrLanguage, Boolean>? = null
        show {
            AsrLanguageDialog(
                AsrLanguageSelection(code = "en", channelName = "Test show", channelId = "show"),
                onDismiss = {},
                onSave = { language, channel -> saved = language to channel },
            )
        }
        listOf("Mandarin Chinese", "Arabic", "Tagalog", "Vietnamese").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Tagalog").performScrollTo().performClick()
        compose.onNodeWithText("All episodes in the Test show channel use this language").performScrollTo().performClick()
        compose.onNodeWithText(activity.get().getString(android.R.string.ok)).performClick()
        compose.runOnIdle { assertEquals(AsrLanguage.TAGALOG to true, saved) }
    }

    @Test
    fun tinyDownloadNoticeNamesTheSelectedArchitecture() {
        show {
            AsrModelDownloadDialog(32_290_152, AsrLanguage.MANDARIN, onDismiss = {}, onDownload = {})
        }
        compose.onNodeWithText("Model information: Moonshine Tiny Streaming — Mandarin Chinese").assertExists()
    }

    @Test
    fun newLanguageNamesRespectTheChineseAppLocaleAndLanguageAliases() {
        show {
            val configuration = Configuration(activity.get().resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            val resources = activity.get().createConfigurationContext(configuration).resources
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides resources) {
                Column {
                    listOf("cmn-Hans-CN", "ar-EG", "fil-PH", "vi-VN").forEach { Text(asrLanguageName(it)) }
                }
            }
        }
        listOf("普通话", "阿拉伯语", "他加禄语", "越南语").forEach { compose.onNodeWithText(it).assertExists() }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent { MaterialTheme { content() } }
        }
        compose.waitForIdle()
    }
}

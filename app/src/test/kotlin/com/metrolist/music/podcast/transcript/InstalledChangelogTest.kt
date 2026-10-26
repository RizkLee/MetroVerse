/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class InstalledChangelogTest {
    @Test
    fun englishAndChineseInstalledNotesDescribeTheCurrentBuildAndLanguages() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val terms = mapOf(
            Locale.ENGLISH to listOf("Mandarin Chinese", "Arabic", "Tagalog", "Vietnamese", "Tiny", "Small", "Android 11", "Apple News Today"),
            Locale.SIMPLIFIED_CHINESE to listOf("普通话", "阿拉伯语", "他加禄语", "越南语", "Tiny", "Small", "Android 11", "Apple News Today"),
        )
        terms.forEach { (locale, languages) ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val notes = context.createConfigurationContext(configuration).getString(R.string.metroverse_changelog_body)
            assertTrue("Version missing for $locale", notes.startsWith("MetroVerse ${BuildConfig.VERSION_NAME}"))
            languages.forEach { language -> assertTrue("$language missing for $locale", language in notes) }
        }
    }
}

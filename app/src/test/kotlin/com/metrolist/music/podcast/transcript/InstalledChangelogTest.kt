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
            Locale.ENGLISH to listOf("podcast subtitle recognition", "ARMv9", "Snapdragon 8 Elite Gen 5"),
            Locale.SIMPLIFIED_CHINESE to listOf("播客端侧字幕", "ARMv9", "骁龙 8 Elite Gen 5", "闪退"),
        )
        terms.forEach { (locale, languages) ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val notes = context.createConfigurationContext(configuration).getString(R.string.metroverse_changelog_body)
            assertTrue("Version missing for $locale", notes.startsWith("MetroVerse ${BuildConfig.VERSION_NAME}"))
            languages.forEach { language -> assertTrue("$language missing for $locale", language in notes) }
        }
    }
}

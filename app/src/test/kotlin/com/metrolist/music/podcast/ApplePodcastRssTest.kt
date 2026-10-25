/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.R
import com.prof18.rssparser.RssParser
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ApplePodcastRssTest {
    @Test
    fun appleStyleRssProvidesTheAudioAndMetadataUsedByTheExistingImporter() = runBlocking {
        val channel = RssParser().parse(
            """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                <channel>
                    <title>Apple News Today</title>
                    <link>https://www.apple.com/apple-news/</link>
                    <description>Daily news.</description>
                    <itunes:author>Apple News</itunes:author>
                    <item>
                        <title>Sample news episode</title>
                        <guid isPermaLink="false">news-episode-1</guid>
                        <pubDate>Sat, 24 Oct 2026 11:00:00 GMT</pubDate>
                        <enclosure url="https://news-assets.apple.com/sample.mp3" type="audio/mpeg" length="1000" />
                        <itunes:duration>12:35</itunes:duration>
                    </item>
                </channel>
            </rss>
            """.trimIndent(),
        )

        assertEquals("Apple News Today", channel.title)
        assertEquals("Apple News", channel.itunesChannelData?.author)
        val episode = channel.items.single()
        assertEquals("https://news-assets.apple.com/sample.mp3", episode.audio)
        assertEquals("news-episode-1", episode.guid)
        assertEquals(755, parsePodcastDuration(episode.itunesItemData?.duration))
        assertTrue(parsePodcastDate(episode.pubDate) != null)
    }

    @Test
    fun unavailableFeedExplanationIsLocalizedInEnglishAndChinese() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun message(locale: Locale): String {
            val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
            return context.createConfigurationContext(config).getString(R.string.podcast_feed_unavailable)
        }

        val english = message(Locale.ENGLISH)
        val chinese = message(Locale.SIMPLIFIED_CHINESE)
        assertTrue(english.contains("RSS"))
        assertTrue(chinese.contains("RSS"))
        assertNotEquals(english, chinese)
    }
}

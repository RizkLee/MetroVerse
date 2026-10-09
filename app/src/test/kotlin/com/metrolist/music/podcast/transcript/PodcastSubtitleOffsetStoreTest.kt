/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PodcastSubtitleOffsetStoreTest {
    @Test
    fun `offsets survive reopening the store and remain isolated per episode`() = runBlocking {
        withTimeout(10_000L) {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val firstEpisode = "offset-test-first-${System.nanoTime()}"
            val secondEpisode = "offset-test-second-${System.nanoTime()}"
            val store = PodcastSubtitleOffsetStore(context)
            assertEquals(0, store.offsetMs(firstEpisode).first())

            assertTrue(store.setOffsetMs(firstEpisode, 750))
            assertTrue(store.setOffsetMs(secondEpisode, -500))
            val reopened = PodcastSubtitleOffsetStore(context)
            assertEquals(750, reopened.offsetMs(firstEpisode).first())
            assertEquals(-500, reopened.offsetMs(secondEpisode).first())

            assertTrue(reopened.setOffsetMs(firstEpisode, 0))
            assertEquals(0, store.offsetMs(firstEpisode).first())
            assertEquals(-500, store.offsetMs(secondEpisode).first())
            assertNull(context.dataStore.data.first()[PodcastSubtitleOffsetStore.keyForEpisode(firstEpisode)])
            assertTrue(reopened.setOffsetMs(secondEpisode, 0))
        }
    }

    @Test
    fun `episode keys are stable and do not embed episode identifiers`() {
        val episodeId = "open-rss:test-episode"
        val firstKey = PodcastSubtitleOffsetStore.keyForEpisode(episodeId)

        assertEquals(firstKey, PodcastSubtitleOffsetStore.keyForEpisode(episodeId))
        assertNotEquals(firstKey, PodcastSubtitleOffsetStore.keyForEpisode("open-rss:other-episode"))
        assertFalse(firstKey.name.contains(episodeId))
    }
}

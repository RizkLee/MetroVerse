/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleRestorationTest {
    private val saved = EpisodeTranscript(
        "episode", TranscriptSource.ON_DEVICE, listOf(SubtitleCue(0, 1000, "saved")),
        isComplete = false, mediaSourceFingerprint = "audio",
    )

    @Test fun `unsaved progress is restored without waiting for an older disk cache`() = runBlocking {
        val restored = restoreEpisodeSubtitle("episode", "audio", { saved }, { error("disk must not delay pending cues") })
        assertEquals(RestoredSubtitle(saved, true), restored)
    }

    @Test fun `a matching incomplete checkpoint is immediately restorable`() = runBlocking {
        assertEquals(RestoredSubtitle(saved, false), restoreEpisodeSubtitle("episode", "audio", { null }, { saved }))
    }

    @Test fun `changed audio never restores an incomplete checkpoint`() = runBlocking {
        assertNull(restoreEpisodeSubtitle("episode", "new-audio", { saved }, { saved }))
    }

    @Test fun `publisher subtitles and complete legacy subtitles remain restorable`() = runBlocking {
        val feed = saved.copy(source = TranscriptSource.FEED, isComplete = true, mediaSourceFingerprint = null)
        assertEquals(RestoredSubtitle(feed, false), restoreEpisodeSubtitle("episode", "audio", { null }, { feed }))
        val legacy = saved.copy(isComplete = true, mediaSourceFingerprint = null)
        assertEquals(RestoredSubtitle(legacy, false), restoreEpisodeSubtitle("episode", "audio", { null }, { legacy }))
    }

    @Test fun `another episode cannot supply cues even when its audio fingerprint matches`() = runBlocking {
        val other = saved.copy(episodeId = "other")
        assertNull(restoreEpisodeSubtitle("episode", "audio", { other }, { other }))
    }
}

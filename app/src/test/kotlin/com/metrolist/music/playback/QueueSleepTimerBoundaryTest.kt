package com.metrolist.music.playback

import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeAlbumRadio
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSleepTimerBoundaryTest {
    @Test
    fun `playlist continuation remains inside collection while album radio does not`() {
        assertTrue(YouTubePlaylistQueue(playlistId = "playlist").continuationBelongsToCurrentCollection)
        assertFalse(YouTubeAlbumRadio(playlistId = "album").continuationBelongsToCurrentCollection)
        assertFalse(ListQueue(items = emptyList()).continuationBelongsToCurrentCollection)
    }

    @Test
    fun `collection boundary excludes already appended similar content`() {
        assertEquals(2, collectionEndBoundaryIndex(originalQueueSize = 3, itemCount = 8, currentIndex = 0))
    }

    @Test
    fun `queue timer duration range stops at collection boundary`() {
        assertEquals(1..2, queueRemainingDurationRange(currentIndex = 0, boundaryIndex = 2, itemCount = 8))
        assertNull(queueRemainingDurationRange(currentIndex = 2, boundaryIndex = 2, itemCount = 8))
        assertNull(queueRemainingDurationRange(currentIndex = 0, boundaryIndex = 0, itemCount = 1))
    }

    @Test
    fun `end of queue blocks similar continuation but keeps playlist pagination`() {
        assertFalse(
            shouldAppendQueueContinuation(
                endOfQueueTimerActive = true,
                continuationBelongsToCurrentCollection = false,
            ),
        )
        assertTrue(
            shouldAppendQueueContinuation(
                endOfQueueTimerActive = true,
                continuationBelongsToCurrentCollection = true,
            ),
        )
        assertTrue(
            shouldAppendQueueContinuation(
                endOfQueueTimerActive = false,
                continuationBelongsToCurrentCollection = false,
            ),
        )
    }

    @Test
    fun `service rejects stale or end-timer automix actions`() {
        assertFalse(
            shouldAcceptAutomixAction(
                endOfQueueTimerActive = true,
                displayedItemId = "similar",
                requestedItemId = "similar",
            ),
        )
        assertFalse(
            shouldAcceptAutomixAction(
                endOfQueueTimerActive = false,
                displayedItemId = null,
                requestedItemId = "similar",
            ),
        )
        assertTrue(
            shouldAcceptAutomixAction(
                endOfQueueTimerActive = false,
                displayedItemId = "similar",
                requestedItemId = "similar",
            ),
        )
    }

    @Test
    fun `automix candidates survive timer gating but stale responses do not`() {
        assertTrue(shouldStoreAutomixResponse(requestGeneration = 4, currentGeneration = 4))
        assertFalse(shouldStoreAutomixResponse(requestGeneration = 3, currentGeneration = 4))
    }

    @Test
    fun `background lyrics loads music only when enabled`() {
        assertTrue(shouldLoadLyricsInBackground(enabled = true, isEpisode = false))
        assertFalse(shouldLoadLyricsInBackground(enabled = false, isEpisode = false))
        assertFalse(shouldLoadLyricsInBackground(enabled = true, isEpisode = true))
    }

    @Test
    fun `disabling background lyrics cancels hidden loads only`() {
        assertTrue(
            shouldCancelLyricsLoadWhenBackgroundDisabled(
                loadLyricsInBackground = false,
                userInitiated = false,
            ),
        )
        assertFalse(
            shouldCancelLyricsLoadWhenBackgroundDisabled(
                loadLyricsInBackground = false,
                userInitiated = true,
            ),
        )
        assertFalse(
            shouldCancelLyricsLoadWhenBackgroundDisabled(
                loadLyricsInBackground = true,
                userInitiated = false,
            ),
        )
    }

    @Test
    fun `background lyrics prefetches only the immediate next item`() {
        assertEquals(3, nextLyricsPrefetchIndex(currentIndex = 2, itemCount = 5))
        assertNull(nextLyricsPrefetchIndex(currentIndex = 4, itemCount = 5))
        assertNull(nextLyricsPrefetchIndex(currentIndex = -1, itemCount = 5))
    }

    @Test
    fun `collection boundary never targets an item before the current item`() {
        assertEquals(4, collectionEndBoundaryIndex(originalQueueSize = 3, itemCount = 8, currentIndex = 4))
    }

    @Test
    fun `collection boundary falls back to the physical queue when origin is unknown`() {
        assertEquals(4, collectionEndBoundaryIndex(originalQueueSize = 0, itemCount = 5, currentIndex = 1))
        assertNull(collectionEndBoundaryIndex(originalQueueSize = 0, itemCount = 0, currentIndex = 0))
    }
}

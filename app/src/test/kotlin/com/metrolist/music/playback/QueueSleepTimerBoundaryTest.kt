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
    fun `collection boundary never targets an item before the current item`() {
        assertEquals(4, collectionEndBoundaryIndex(originalQueueSize = 3, itemCount = 8, currentIndex = 4))
    }

    @Test
    fun `collection boundary falls back to the physical queue when origin is unknown`() {
        assertEquals(4, collectionEndBoundaryIndex(originalQueueSize = 0, itemCount = 5, currentIndex = 1))
        assertNull(collectionEndBoundaryIndex(originalQueueSize = 0, itemCount = 0, currentIndex = 0))
    }
}

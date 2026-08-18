package com.metrolist.music.playback

import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeAlbumRadio
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSleepTimerBoundaryTest {
    @Test
    fun `playlist continuation remains inside collection while album radio does not`() {
        assertTrue(YouTubePlaylistQueue(playlistId = "playlist").continuationBelongsToCurrentCollection)
        assertFalse(YouTubeAlbumRadio(playlistId = "album").continuationBelongsToCurrentCollection)
        assertFalse(ListQueue(items = emptyList()).continuationBelongsToCurrentCollection)
    }
}

package com.metrolist.music.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodePositionRestorerTest {
    @Test fun `repeat one remains at zero instead of restoring the saved episode tail`() = runBlocking {
        var position = 0L
        var reads = 0
        val restorer = EpisodePositionRestorer(this, { reads++; 3_595_000L }, { "episode" }, { position = it })
        repeat(3) {
            position = 0L
            restorer.onTransition("episode", Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            delay(150)
            assertEquals(0L, position)
        }
        assertEquals(0, reads)
    }

    @Test fun `ordinary episode entry still restores progress`() = runBlocking {
        val sought = CompletableDeferred<Long>()
        val restorer = EpisodePositionRestorer(this, { 42_000L }, { "episode" }, { sought.complete(it) })
        restorer.onTransition("episode", Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(42_000L, kotlinx.coroutines.withTimeout(5_000) { sought.await() })
    }

    @Test fun `an old read cannot seek a later repeat of the same episode`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seeks = mutableListOf<Long>()
        val restorer = EpisodePositionRestorer(
            this,
            { withContext(NonCancellable) { reading.complete(Unit); release.await(); 3_595_000L } },
            { "episode" },
            { seeks += it },
        )
        restorer.onTransition("episode", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        kotlinx.coroutines.withTimeout(5_000) { reading.await() }
        restorer.onTransition("episode", Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        release.complete(Unit)
        delay(150)
        assertEquals(emptyList<Long>(), seeks)
    }

    @Test fun `leaving an episode cancels its delayed resume`() = runBlocking {
        val seeks = mutableListOf<Long>()
        val restorer = EpisodePositionRestorer(this, { 42_000L }, { "episode" }, { seeks += it })
        restorer.onTransition("episode", Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        restorer.onTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        delay(150)
        assertEquals(emptyList<Long>(), seeks)
    }
}

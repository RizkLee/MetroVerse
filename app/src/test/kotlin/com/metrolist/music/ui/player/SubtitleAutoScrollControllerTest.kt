/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoScrollControllerTest {
    @Test
    fun `seeking resumes following immediately and cancels the drag timer`() = runBlocking {
        val dragTimer = CompletableDeferred<Unit>()
        val controller = SubtitleAutoScrollController(this) { dragTimer.await() }
        controller.pause()
        controller.resumeAfterDrag()
        yield()

        controller.resume()
        assertTrue(controller.following.value)

        controller.pause()
        dragTimer.complete(Unit)
        yield()
        assertFalse(controller.following.value)
    }

    @Test
    fun `a new drag cancels the previous automatic resume`() = runBlocking {
        val dragTimer = CompletableDeferred<Unit>()
        val controller = SubtitleAutoScrollController(this) { dragTimer.await() }
        controller.pause()
        controller.resumeAfterDrag()
        yield()

        controller.pause()
        dragTimer.complete(Unit)
        yield()
        assertFalse(controller.following.value)
    }

    @Test
    fun `finishing a drag resumes following after the delay`() = runBlocking {
        val dragTimer = CompletableDeferred<Unit>()
        val controller = SubtitleAutoScrollController(this) { dragTimer.await() }
        controller.pause()
        controller.resumeAfterDrag()
        yield()
        assertFalse(controller.following.value)

        dragTimer.complete(Unit)
        yield()
        assertTrue(controller.following.value)
    }
}

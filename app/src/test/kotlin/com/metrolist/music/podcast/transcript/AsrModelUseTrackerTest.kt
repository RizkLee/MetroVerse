/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AsrModelUseTrackerTest {
    private val small = AsrLanguage.ENGLISH.model(AsrModelVariant.SMALL_STREAMING)
    private val tiny = AsrLanguage.ENGLISH.model(AsrModelVariant.TINY_STREAMING)

    @Test
    fun cancellingAReplacementWaitingForOldCleanupReleasesBothModels() = runBlocking {
        withTimeout(5_000) {
            val uses = AsrModelUseTracker()
            val started = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val stopping = launch(start = CoroutineStart.LAZY) {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { releaseOld.await() } }
            }
            uses.track(stopping, small)
            stopping.start()
            started.await()
            stopping.cancel()
            var replacementEntered = false
            val replacement = launch(start = CoroutineStart.LAZY) {
                withContext(NonCancellable) { stopping.join() }
                currentCoroutineContext().ensureActive()
                replacementEntered = true
            }
            uses.track(replacement, tiny)
            assertEquals(setOf(small, tiny), uses.models.value)
            replacement.start()
            yield()
            replacement.cancel()
            releaseOld.complete(Unit)
            joinAll(stopping, replacement)
            assertFalse(replacementEntered)
            assertEquals(emptySet<AsrModel>(), uses.models.value)
        }
    }

    @Test
    fun cancellationBeforeTheJobStartsReleasesItsReservedModel() = runBlocking {
        val uses = AsrModelUseTracker()
        var entered = false
        val waiting = launch(start = CoroutineStart.LAZY) { entered = true }
        uses.track(waiting, tiny)
        assertEquals(setOf(tiny), uses.models.value)
        waiting.cancelAndJoin()
        assertFalse(entered)
        assertEquals(emptySet<AsrModel>(), uses.models.value)
    }

    @Test
    fun overlappingJobsUsingTheSameModelMustBothFinishBeforeDeletion() {
        val uses = AsrModelUseTracker()
        val first = Job()
        val second = Job()
        uses.track(first, small)
        uses.track(second, small)
        first.complete()
        assertEquals(setOf(small), uses.models.value)
        second.cancel()
        assertEquals(emptySet<AsrModel>(), uses.models.value)
    }
}

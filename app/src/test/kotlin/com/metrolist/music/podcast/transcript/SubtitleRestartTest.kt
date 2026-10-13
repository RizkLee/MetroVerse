/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRestartTest {
    @Test fun `both cancelled writers finish saving before deletion and replacement recognition`() = runBlocking {
        val recognitionFlush = CompletableDeferred<Unit>()
        val translationFlush = CompletableDeferred<Unit>()
        val awaitingTranslation = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val recognition = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { recognitionFlush.await(); events += "recognition saved" }
            }
        }
        val translation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { translationFlush.await(); events += "translation saved" }
            }
        }
        recognition.cancel()
        translation.cancel()
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            val result = clearSubtitlesAfterWriters(
                recognition,
                awaitTranslations = { awaitingTranslation.complete(Unit); translation.join() },
                isCurrent = { true },
                clear = { events += "deleted"; Result.success(Unit) },
            )
            if (result?.isSuccess == true) events += "replacement started"
        }
        assertTrue(events.isEmpty())
        recognitionFlush.complete(Unit)
        awaitingTranslation.await()
        assertEquals(listOf("recognition saved"), events)
        assertFalse(restart.isCompleted)
        translationFlush.complete(Unit)
        restart.await()
        assertEquals(listOf("recognition saved", "translation saved", "deleted", "replacement started"), events)
    }

    @Test fun `cancelling restart while checkpoint is flushing never deletes or starts replacement`() = runBlocking {
        val flush = CompletableDeferred<Unit>()
        var saved = false
        var deleted = false
        val recognition = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { flush.await(); saved = true }
        }
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            clearSubtitlesAfterWriters(recognition, {}, { true }) { deleted = true; Result.success(Unit) }
        }
        restart.cancel()
        assertFalse(saved)
        assertFalse(deleted)
        flush.complete(Unit)
        restart.join()
        assertTrue(saved)
        assertFalse(deleted)
        assertTrue(restart.isCancelled)
    }

    @Test fun `leaving and returning to the same episode still invalidates the waiting restart`() = runBlocking {
        val flush = CompletableDeferred<Unit>()
        var generation = 1L
        var episode: String? = "A"
        var deleted = false
        val recognition = launch(start = CoroutineStart.UNDISPATCHED) { flush.await() }
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            clearSubtitlesAfterWriters(recognition, {}, { isCurrentSubtitleRequest(1, generation, "A", episode) }) {
                deleted = true
                Result.success(Unit)
            }
        }
        generation = 2; episode = "B"
        assertFalse(isCurrentSubtitleRequest(1, generation, "A", episode))
        generation = 3; episode = "A"
        flush.complete(Unit)
        assertNull(restart.await())
        assertFalse(deleted)
    }

    @Test fun `episode switch during translation flush also prevents deletion`() = runBlocking {
        val flush = CompletableDeferred<Unit>()
        var current = true
        var deleted = false
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            clearSubtitlesAfterWriters(null, { flush.await() }, { current }) {
                deleted = true
                Result.success(Unit)
            }
        }
        current = false
        flush.complete(Unit)
        assertNull(restart.await())
        assertFalse(deleted)
    }
}

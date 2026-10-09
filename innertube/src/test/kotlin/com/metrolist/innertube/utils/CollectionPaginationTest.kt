package com.metrolist.innertube.utils

import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.PlaylistPage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPaginationTest {
    @Test
    fun `strict pagination collects every page including an isolated empty page`() = runBlocking {
        val requested = mutableListOf<String>()
        val pages = mapOf(
            "first" to ContinuationItems(emptyList(), "second"),
            "second" to ContinuationItems(listOf(2, 3), null),
        )
        val items = collectContinuationItems(listOf(1), "first", requireComplete = true) {
            requested += it
            Result.success(pages.getValue(it))
        }

        assertEquals(listOf(1, 2, 3), items)
        assertEquals(listOf("first", "second"), requested)
    }

    @Test
    fun `failed continuation preserves partial UI results but fails strict reads without request details`() = runBlocking {
        val failure = IOException("https://example.invalid/?continuation=private-token")
        assertEquals(
            listOf(1),
            collectContinuationItems(listOf(1), "private-token", requireComplete = false) { Result.failure(failure) },
        )
        val error = runCatching {
            collectContinuationItems(listOf(1), "private-token", requireComplete = true) { Result.failure(failure) }
        }.exceptionOrNull()

        assertTrue(error is IncompleteCollectionException)
        assertFalse(error!!.message.orEmpty().contains("private-token"))
        assertFalse(error.message.orEmpty().contains("example.invalid"))
        assertNull(error.cause)
    }

    @Test
    fun `repeated continuation fails strict reads and stops UI pagination`() = runBlocking {
        for (strict in listOf(false, true)) {
            var requests = 0
            val result = runCatching {
                collectContinuationItems(listOf(1), "repeat", requireComplete = strict) {
                    requests++
                    Result.success(ContinuationItems(listOf(2), "repeat"))
                }
            }
            assertEquals(1, requests)
            if (strict) assertTrue(result.exceptionOrNull() is IncompleteCollectionException)
            else assertEquals(listOf(1, 2), result.getOrThrow())
        }
    }

    @Test
    fun `fifty request limit cannot pretend that strict pagination is complete`() = runBlocking {
        for (strict in listOf(false, true)) {
            var requests = 0
            val result = runCatching {
                collectContinuationItems(listOf(0), "start", requireComplete = strict) {
                    requests++
                    Result.success(ContinuationItems(listOf(requests), requests.toString()))
                }
            }
            assertEquals(50, requests)
            if (strict) assertTrue(result.exceptionOrNull() is IncompleteCollectionException)
            else assertEquals((0..50).toList(), result.getOrThrow())
        }
    }

    @Test
    fun `a terminal fiftieth page is a complete strict collection`() = runBlocking {
        var requests = 0
        val items = collectContinuationItems(listOf(0), "start", requireComplete = true) {
            requests++
            Result.success(ContinuationItems(listOf(requests), if (requests == 50) null else requests.toString()))
        }

        assertEquals(50, requests)
        assertEquals((0..50).toList(), items)
    }

    @Test
    fun `two empty pages with more data pending fail strict reads`() = runBlocking {
        for (strict in listOf(false, true)) {
            var requests = 0
            val result = runCatching {
                collectContinuationItems(listOf(1), "start", requireComplete = strict) {
                    requests++
                    Result.success(ContinuationItems(emptyList(), requests.toString()))
                }
            }
            assertEquals(2, requests)
            if (strict) assertTrue(result.exceptionOrNull() is IncompleteCollectionException)
            else assertEquals(listOf(1), result.getOrThrow())
        }
    }

    @Test
    fun `two empty pages ending the collection are complete`() = runBlocking {
        var requests = 0
        val items = collectContinuationItems(listOf(1), "start", requireComplete = true) {
            requests++
            Result.success(ContinuationItems(emptyList(), if (requests == 2) null else "last"))
        }

        assertEquals(2, requests)
        assertEquals(listOf(1), items)
    }

    @Test
    fun `thrown and wrapped continuation cancellations propagate in both modes`() = runBlocking {
        for (strict in listOf(false, true)) {
            for (wrapped in listOf(false, true)) {
                val cancelled = CancellationException("cancelled")
                val error = runCatching {
                    collectContinuationItems(listOf(1), "start", requireComplete = strict) {
                        if (wrapped) Result.failure(cancelled) else throw cancelled
                    }
                }.exceptionOrNull()
                assertSame(cancelled, error)
            }
        }
    }

    @Test
    fun `initial playlist and library cancellations propagate in both modes`() = runBlocking {
        for (strict in listOf(false, true)) {
            val cancelled = CancellationException("cancelled")
            assertSame(
                cancelled,
                runCatching { Result.failure<PlaylistPage>(cancelled).completed(strict) }.exceptionOrNull(),
            )
            assertSame(
                cancelled,
                runCatching { Result.failure<LibraryPage>(cancelled).completed(strict) }.exceptionOrNull(),
            )
        }
    }

    @Test
    fun `initial strict failures are sanitized while default failures are retained`() = runBlocking {
        val failure = IOException("https://example.invalid/private")
        val playlist = Result.failure<PlaylistPage>(failure)
        val library = Result.failure<LibraryPage>(failure)
        assertSame(failure, playlist.completed().exceptionOrNull())
        assertSame(failure, library.completed().exceptionOrNull())
        for (error in listOf(playlist.completed(true).exceptionOrNull(), library.completed(true).exceptionOrNull())) {
            assertTrue(error is IncompleteCollectionException)
            assertNull(error!!.cause)
            assertFalse(error.message.orEmpty().contains("example.invalid"))
        }
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import com.metrolist.music.api.TranslationRequestException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationCoordinatorTest {
    private val config = SubtitleTranslationConfig("OpenRouter", "test-key", "", "default", "", "model", "standard", "de", "prompt")
    private val cues = (0 until 130).map { index -> SubtitleCue(index * 1_000L, index * 1_000L + 900, "cue-$index") }

    private data class Request(val lines: List<String>, val response: CompletableDeferred<Result<List<String>>> = CompletableDeferred())

    private class Harness(scope: CoroutineScope, initialCache: Map<String, String> = emptyMap()) {
        var now = 0L
        var currentCalls = 0
        var maximumCalls = 0
        var totalCalls = 0
        var storeCalls = 0
        var failStore = false
        var pendingSave = emptyMap<String, String>()
        var visible = emptyMap<Int, String>()
        var loads = 0
        val statuses = mutableListOf<PodcastTranslationStatus>()
        val requests = Channel<Request>(Channel.UNLIMITED)
        val saved = initialCache.toMutableMap()
        val coordinator = SubtitleTranslationCoordinator(
            scope = scope,
            load = { _, _ -> loads++; saved + pendingSave },
            store = { _, _, lines ->
                storeCalls++
                if (failStore) {
                    pendingSave = lines
                    false
                } else {
                    saved.putAll(lines)
                    pendingSave = emptyMap()
                    true
                }
            },
            translate = { lines, _ ->
                currentCalls++
                totalCalls++
                maximumCalls = maxOf(maximumCalls, currentCalls)
                try {
                    val request = Request(lines)
                    requests.send(request)
                    request.response.await()
                } finally {
                    currentCalls--
                }
            },
            onTranslations = { visible = it },
            onStatus = statuses::add,
            errorMessage = { "translation failed" },
            saveErrorMessage = { "save failed" },
            hasPendingSave = { _, _ -> pendingSave.isNotEmpty() },
            nowMs = { now },
        )

        suspend fun settle() { repeat(6) { yield() } }

        suspend fun close() {
            coordinator.stop()
            coordinator.awaitStopped()
            requests.close()
        }
    }

    @Test
    fun `busy request retains the newest position and success notice does not hold the worker`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.coordinator.request("episode", 0, cues, config)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues, config)
                val first = harness.requests.receive()
                harness.now = 2_000
                harness.coordinator.request("episode", 70, cues, config)
                first.response.complete(Result.success(first.lines.map { "translated $it" }))
                harness.settle()
                harness.now = 4_000
                harness.coordinator.request("episode", 70, cues, config)
                val next = harness.requests.receive()
                assertTrue("cue-70" in next.lines)
                assertFalse("cue-24" in next.lines)
                assertTrue(PodcastTranslationStatus.Success in harness.statuses)
                assertEquals(1, harness.maximumCalls)
            } finally { harness.close() }
        }
    }

    @Test
    fun `restored short prefix does not discard paid translations for later cues`() = runBlocking {
        withTimeout(5_000) {
            val cached = cues.associate { subtitleCueFingerprint(it) to "translated ${it.text}" }
            val harness = Harness(this, cached)
            try {
                harness.coordinator.request("episode", 0, cues.take(1), config)
                harness.settle()
                assertEquals(1, harness.visible.size)
                harness.coordinator.request("episode", 30, cues.take(80), config)
                harness.settle()
                assertEquals(80, harness.visible.size)
                assertEquals("translated cue-79", harness.visible[79])
                assertEquals(1, harness.loads)
                assertTrue(harness.requests.tryReceive().isFailure)
            } finally { harness.close() }
        }
    }

    @Test
    fun `changed source at an existing index cannot inherit an in-flight translation`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.coordinator.request("episode", 0, cues.take(1), config)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues.take(1), config)
                val request = harness.requests.receive()
                harness.coordinator.updateCues(listOf(cues.first().copy(text = "different source")))
                request.response.complete(Result.success(listOf("old translation")))
                harness.settle()
                assertTrue(harness.visible.isEmpty())
                assertEquals("old translation", harness.saved[subtitleCueFingerprint(cues.first())])
            } finally { harness.close() }
        }
    }

    @Test
    fun `closing demand cancels the provider and prevents late cache or UI changes`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.coordinator.request("episode", 0, cues, config)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues, config)
                val request = harness.requests.receive()
                harness.coordinator.stop()
                harness.coordinator.awaitStopped()
                request.response.complete(Result.success(request.lines.map { "late" }))
                harness.settle()
                assertEquals(0, harness.currentCalls)
                assertTrue(harness.visible.isEmpty())
                assertTrue(harness.saved.isEmpty())
                assertEquals(PodcastTranslationStatus.Idle, harness.statuses.last())
            } finally { harness.close() }
        }
    }

    @Test
    fun `rate-limit cooldown survives new windows and ASR updates`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.coordinator.request("episode", 0, cues.take(10), config)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues.take(10), config)
                val request = harness.requests.receive()
                request.response.complete(Result.failure(TranslationRequestException(429, 120_000, "limited")))
                harness.settle()
                harness.now = 100_000
                harness.coordinator.request("episode", 80, cues, config)
                harness.coordinator.updateCues(cues)
                harness.settle()
                assertTrue(harness.requests.tryReceive().isFailure)
                assertTrue(harness.statuses.last() is PodcastTranslationStatus.Error)
            } finally { harness.close() }
        }
    }

    @Test
    fun `one new cue every four seconds becomes one later batch with no lost early pending cue`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.coordinator.request("episode", 0, cues.take(1), config, isStreaming = true)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues.take(1), config, isStreaming = true)
                val first = harness.requests.receive()
                first.response.complete(Result.success(listOf("first")))
                harness.settle()
                for (index in 1..3) {
                    harness.now = index * 4_000L
                    harness.coordinator.request("episode", index, cues.take(index + 1), config, isStreaming = true)
                    harness.settle()
                    assertTrue("No per-cue request at index $index", harness.requests.tryReceive().isFailure)
                }
                harness.now = 16_000
                harness.coordinator.request("episode", 4, cues.take(5), config, isStreaming = true)
                val batch = harness.requests.receive()
                assertEquals(listOf("cue-1", "cue-2", "cue-3", "cue-4"), batch.lines)
            } finally { harness.close() }
        }
    }

    @Test
    fun `one new cue every eight seconds coalesces and an EOF flush retains its last pending cue`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.coordinator.request("episode", 0, cues.take(1), config, isStreaming = true)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues.take(1), config, isStreaming = true)
                val first = harness.requests.receive()
                first.response.complete(Result.success(listOf("first")))
                harness.settle()
                for (index in 1..2) {
                    harness.now = index * 8_000L
                    harness.coordinator.request("episode", index, cues.take(index + 1), config, isStreaming = true)
                    harness.settle()
                    assertTrue(harness.requests.tryReceive().isFailure)
                }
                harness.now = 20_000
                harness.coordinator.request("episode", 2, cues.take(3), config, isStreaming = true)
                val batch = harness.requests.receive()
                assertEquals(listOf("cue-1", "cue-2"), batch.lines)
                batch.response.complete(Result.success(listOf("second", "third")))
                harness.settle()
                harness.now = 24_000
                harness.coordinator.request("episode", 3, cues.take(4), config, isStreaming = true)
                harness.settle()
                assertTrue(harness.requests.tryReceive().isFailure)
                harness.now = 25_000
                harness.coordinator.updateCues(cues.take(4), isStreaming = false)
                assertEquals(listOf("cue-3"), harness.requests.receive().lines)
            } finally { harness.close() }
        }
    }

    @Test
    fun `three failed attempts stop until an explicit retry despite new windows`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.coordinator.request("episode", 0, cues, config)
                harness.settle()
                for (attempt in 0..2) {
                    harness.now = attempt * 100_000L + 1_000
                    harness.coordinator.request("episode", 0, cues, config)
                    harness.settle()
                    harness.now += 1_000
                    harness.coordinator.request("episode", 0, cues, config)
                    val request = harness.requests.receive()
                    request.response.complete(Result.failure(IllegalArgumentException("invalid response")))
                    harness.settle()
                }
                harness.now = 9_000_000
                harness.coordinator.request("episode", 90, cues, config)
                harness.coordinator.updateCues(cues)
                harness.settle()
                assertTrue(harness.requests.tryReceive().isFailure)
                harness.coordinator.retry()
                harness.settle()
                harness.now += 1_000
                harness.coordinator.request("episode", 90, cues, config)
                assertTrue("cue-90" in harness.requests.receive().lines)
            } finally { harness.close() }
        }
    }

    @Test
    fun `retry after a cache write failure only saves the already paid translation`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.failStore = true
                harness.coordinator.request("episode", 0, cues.take(1), config)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues.take(1), config)
                val request = harness.requests.receive()
                request.response.complete(Result.success(listOf("paid translation")))
                harness.settle()
                assertEquals(PodcastTranslationStatus.Error("save failed"), harness.statuses.last())
                assertEquals("paid translation", harness.visible[0])
                assertEquals(1, harness.storeCalls)
                assertTrue(harness.saved.isEmpty())

                harness.failStore = false
                harness.coordinator.retry()
                harness.settle()
                assertEquals(2, harness.storeCalls)
                assertEquals(1, harness.totalCalls)
                assertEquals(PodcastTranslationStatus.Success, harness.statuses.last())
                assertEquals("paid translation", harness.saved[subtitleCueFingerprint(cues.first())])
                assertTrue(harness.requests.tryReceive().isFailure)
            } finally { harness.close() }
        }
    }

    @Test
    fun `reload keeps a failed save retryable without paying for the same lines again`() = runBlocking {
        withTimeout(5_000) {
            val harness = Harness(this)
            try {
                harness.failStore = true
                harness.coordinator.request("episode", 0, cues.take(1), config)
                harness.settle()
                harness.now = 1_000
                harness.coordinator.request("episode", 0, cues.take(1), config)
                harness.requests.receive().response.complete(Result.success(listOf("paid translation")))
                harness.settle()
                harness.coordinator.clear()
                harness.coordinator.awaitStopped()
                harness.coordinator.request("episode", 0, cues.take(1), config)
                harness.settle()
                assertEquals("paid translation", harness.visible[0])
                assertEquals(PodcastTranslationStatus.Error("save failed"), harness.statuses.last())

                harness.failStore = false
                harness.coordinator.retry()
                harness.settle()
                assertEquals(1, harness.totalCalls)
                assertEquals(2, harness.storeCalls)
                assertTrue(harness.pendingSave.isEmpty())
                assertEquals(PodcastTranslationStatus.Success, harness.statuses.last())
            } finally { harness.close() }
        }
    }

    @Test
    fun `reopening the same episode during a successful save does not report a stale save failure`() = runBlocking {
        withTimeout(5_000) {
            var now = 0L
            var providerCalls = 0
            var storeCalls = 0
            var loads = 0
            val statuses = mutableListOf<PodcastTranslationStatus>()
            val saved = mutableMapOf<String, String>()
            val storeStarted = CompletableDeferred<Unit>()
            val releaseStore = CompletableDeferred<Unit>()
            val coordinator = SubtitleTranslationCoordinator(
                scope = this,
                load = { _, _ -> loads++; saved.toMap() },
                store = { _, _, lines ->
                    storeCalls++
                    storeStarted.complete(Unit)
                    releaseStore.await()
                    saved.putAll(lines)
                    true
                },
                translate = { lines, _ ->
                    providerCalls++
                    Result.success(lines.map { "translated $it" })
                },
                onTranslations = {},
                onStatus = statuses::add,
                errorMessage = { "error" },
                saveErrorMessage = { "save failed" },
                nowMs = { now },
            )
            try {
                coordinator.request("episode", 0, cues.take(1), config)
                repeat(6) { yield() }
                now = 1_000
                coordinator.request("episode", 0, cues.take(1), config)
                storeStarted.await()
                coordinator.stop()
                coordinator.request("episode", 0, cues.take(1), config)
                repeat(6) { yield() }
                assertEquals(1, providerCalls)
                assertEquals(1, storeCalls)

                releaseStore.complete(Unit)
                repeat(12) { yield() }
                assertFalse(statuses.any { it is PodcastTranslationStatus.Error })
                assertEquals(1, providerCalls)
                assertEquals(1, storeCalls)
                assertEquals(1, loads)
                assertEquals("translated cue-0", saved[subtitleCueFingerprint(cues.first())])
            } finally {
                releaseStore.complete(Unit)
                coordinator.stop()
                coordinator.awaitStopped()
            }
        }
    }

    @Test
    fun `a cancelled intermediate episode still waits for the original cache write before starting the latest`() = runBlocking {
        withTimeout(5_000) {
            var now = 0L
            val loads = mutableListOf<String>()
            val owners = mutableListOf<String?>()
            val storeStarted = CompletableDeferred<Unit>()
            val releaseStore = CompletableDeferred<Unit>()
            val coordinator = SubtitleTranslationCoordinator(
                scope = this,
                load = { episode, _ -> loads += episode; emptyMap() },
                store = { episode, _, _ ->
                    if (episode == "a") {
                        storeStarted.complete(Unit)
                        releaseStore.await()
                    }
                    true
                },
                translate = { lines, _ -> Result.success(lines.map { "translated $it" }) },
                onTranslations = {},
                onStatus = {},
                errorMessage = { "error" },
                onWorkEpisodeChanged = owners::add,
                nowMs = { now },
            )
            try {
                coordinator.request("a", 0, cues.take(1), config)
                repeat(6) { yield() }
                now = 1_000
                coordinator.request("a", 0, cues.take(1), config)
                storeStarted.await()
                coordinator.request("b", 0, cues.take(1), config)
                repeat(6) { yield() }
                coordinator.request("c", 0, cues.take(1), config)
                repeat(6) { yield() }
                assertEquals(listOf("a"), loads)
                assertEquals(listOf("a"), owners)
                releaseStore.complete(Unit)
                repeat(12) { yield() }
                assertEquals(listOf("a", "c"), loads)
                assertEquals(listOf("a", null, "c"), owners)
            } finally {
                releaseStore.complete(Unit)
                coordinator.stop()
                coordinator.awaitStopped()
            }
            assertEquals(listOf("a", null, "c", null), owners)
        }
    }
}

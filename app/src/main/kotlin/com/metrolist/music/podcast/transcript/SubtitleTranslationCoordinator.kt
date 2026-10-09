/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import com.metrolist.music.api.TranslationRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Its owner serializes calls on one dispatcher; network and file functions may suspend. */
internal class SubtitleTranslationCoordinator(
    private val scope: CoroutineScope,
    private val load: suspend (String, String) -> Map<String, String>,
    private val store: suspend (String, String, Map<String, String>) -> Boolean,
    private val translate: suspend (List<String>, SubtitleTranslationConfig) -> Result<List<String>>,
    private val onTranslations: (Map<Int, String>) -> Unit,
    private val onStatus: (PodcastTranslationStatus) -> Unit,
    private val errorMessage: () -> String,
    private val saveErrorMessage: () -> String = errorMessage,
    private val hasPendingSave: suspend (String, String) -> Boolean = { _, _ -> false },
    private val onWorkEpisodeChanged: (String?) -> Unit = {},
    private val trace: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Demand(
        val episodeId: String,
        val cacheKey: String,
        val requestKey: String,
        val config: SubtitleTranslationConfig,
        val cues: List<TranslationCue>,
        val centerIndex: Int,
        val isStreaming: Boolean,
        val prioritizeSeek: Boolean,
    )

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val cueKeys = mutableMapOf<SubtitleCue, String>()
    private val translations = mutableMapOf<String, String>()
    private val streamingPendingKeys = linkedSetOf<String>()
    private val cooldowns = linkedMapOf<String, SubtitleTranslationCooldown>()
    private var latest: Demand? = null
    private var cacheIdentity: Pair<String, String>? = null
    private var cacheLoaded = false
    private var dirtyCache = false
    private var retrySaveRequested = false
    private var worker: Job? = null
    private var statusJob: Job? = null
    private var generation = 0L
    private var statusShown = false
    private var currentStatus: PodcastTranslationStatus = PodcastTranslationStatus.Idle

    fun request(episodeId: String, centerIndex: Int, cues: List<SubtitleCue>, config: SubtitleTranslationConfig, isStreaming: Boolean = false) {
        if (cues.isEmpty() || !config.hasCredentials) {
            stop()
            return
        }
        val cacheKey = config.cacheFingerprint()
        val requestKey = subtitleCacheFingerprint(episodeId, config.requestFingerprint())
        val identity = episodeId to cacheKey
        if (identity != cacheIdentity) {
            cacheIdentity = identity
            cacheLoaded = false
            dirtyCache = false
            retrySaveRequested = false
            translations.clear()
            cueKeys.clear()
            streamingPendingKeys.clear()
        }
        val demand = Demand(
            episodeId, cacheKey, requestKey, config,
            cues.map { cue -> TranslationCue(cue, cueKeys.getOrPut(cue) { subtitleCueFingerprint(cue) }) },
            centerIndex,
            isStreaming,
            latest?.takeIf { it.requestKey == requestKey }?.let {
                it.prioritizeSeek || (kotlin.math.abs(it.centerIndex - centerIndex) >= SEEK_PRIORITY_DISTANCE &&
                    it.cues.getOrNull(it.centerIndex)?.cue != cues.getOrNull(centerIndex))
            } ?: false,
        )
        val restart = latest?.requestKey != requestKey || worker?.isActive != true
        if (demand.prioritizeSeek) streamingPendingKeys.clear()
        val availableKeys = demand.cues.mapTo(mutableSetOf()) { it.key }
        streamingPendingKeys.retainAll(availableKeys)
        if (isStreaming && centerIndex >= cues.lastIndex - 3 && !demand.prioritizeSeek) {
            val firstPending = (centerIndex - 1).coerceIn(0, cues.lastIndex)
            demand.cues.subList(firstPending, demand.cues.size).forEach { cue ->
                if (cue.key !in translations && cue.cue.text.isNotBlank()) streamingPendingKeys += cue.key
            }
        }
        latest = demand
        publishTranslations(demand)
        if (restart) {
            val previousWorker = worker
            previousWorker?.cancel()
            generation++
            val owner = generation
            statusShown = false
            publishStatus(PodcastTranslationStatus.Idle, owner)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                // A changed configuration must not overlap an HTTP call still being cancelled.
                withContext(NonCancellable) { previousWorker?.join() }
                currentCoroutineContext().ensureActive()
                val episode = latest?.episodeId?.takeIf { owner == generation } ?: return@launch
                onWorkEpisodeChanged(episode)
                try {
                    runWorker(owner)
                } finally {
                    onWorkEpisodeChanged(null)
                }
            }
            worker = job
            job.start()
        }
        wakeups.trySend(Unit)
    }

    /** Stops future work while retaining content-addressed translations for the same episode. */
    fun stop() {
        latest = null
        generation++
        worker?.cancel()
        statusJob?.cancel()
        statusJob = null
        currentStatus = PodcastTranslationStatus.Idle
        onStatus(currentStatus)
    }

    fun clear() {
        stop()
        cacheIdentity = null
        cacheLoaded = false
        dirtyCache = false
        retrySaveRequested = false
        translations.clear()
        cueKeys.clear()
        streamingPendingKeys.clear()
        onTranslations(emptyMap())
    }

    fun updateCues(cues: List<SubtitleCue>, isStreaming: Boolean? = null) {
        val demand = latest ?: return
        val previousCenter = demand.cues.getOrNull(demand.centerIndex)?.cue
        val centerIndex = previousCenter?.let { previous ->
            cues.indexOf(previous).takeIf { it >= 0 } ?: PodcastAsrSupport.cueIndexAt(cues, previous.startMs)
        } ?: demand.centerIndex
        request(demand.episodeId, centerIndex, cues, demand.config, isStreaming ?: demand.isStreaming)
    }

    fun retry() {
        val demand = latest ?: return
        cooldowns[demand.requestKey]?.succeeded()
        retrySaveRequested = dirtyCache
        statusShown = false
        wakeups.trySend(Unit)
    }

    suspend fun awaitStopped() {
        worker?.join()
    }

    private suspend fun runWorker(owner: Long) {
        var pendingSince: Long? = null
        while (currentCoroutineContext().isActive && owner == generation) {
            var demand = latest ?: return
            if (!cacheLoaded) {
                val persisted = load(demand.episodeId, demand.cacheKey)
                val pendingSave = hasPendingSave(demand.episodeId, demand.cacheKey)
                if (!owns(owner)) return
                translations.putAll(persisted)
                dirtyCache = pendingSave
                cacheLoaded = true
                latest?.let(::publishTranslations)
                demand = latest ?: return
            }
            if (dirtyCache && currentStatus == PodcastTranslationStatus.Idle) {
                publishStatus(PodcastTranslationStatus.Error(saveErrorMessage()), owner)
            }
            if (retrySaveRequested) {
                retrySaveRequested = false
                if (dirtyCache) {
                    val saved = persistTranslations(demand)
                    publishStatus(
                        if (saved) PodcastTranslationStatus.Success else PodcastTranslationStatus.Error(saveErrorMessage()),
                        owner,
                        hideAfterMs = 3_000L.takeIf { saved },
                    )
                    continue
                }
            }
            val cooldown = cooldowns.getOrPut(demand.requestKey) { SubtitleTranslationCooldown() }
            while (cooldowns.size > MAX_RETAINED_COOLDOWNS) cooldowns.remove(cooldowns.keys.first())
            val now = nowMs()
            if (cooldown.requiresExplicitRetry) {
                if (currentStatus !is PodcastTranslationStatus.Error) publishStatus(PodcastTranslationStatus.Error(errorMessage()), owner)
                wakeups.receive()
                continue
            }
            if (now < cooldown.retryAtMs) {
                awaitDemand(cooldown.retryAtMs - now)
                continue
            }
            val pendingAt = pendingSince ?: now.also { pendingSince = it }
            when (val plan = planSubtitleTranslation(
                demand.cues, translations.keys, demand.centerIndex, now, pendingAt, cooldown.lastRequestMs,
                demand.isStreaming, demand.prioritizeSeek,
                demand.cues.indices.filterTo(mutableSetOf()) { demand.cues[it].key in streamingPendingKeys },
            )) {
                SubtitleTranslationPlan.Idle -> {
                    pendingSince = null
                    wakeups.receive()
                }
                is SubtitleTranslationPlan.Wait -> awaitDemand(plan.untilMs - now)
                is SubtitleTranslationPlan.Batch -> {
                    val notify = !statusShown || currentStatus is PodcastTranslationStatus.Error
                    if (notify) {
                        statusShown = true
                        publishStatus(PodcastTranslationStatus.Translating, owner)
                    }
                    val inputs = plan.indices.map { demand.cues[it] }
                    latest = demand.copy(prioritizeSeek = false)
                    cooldown.started(nowMs())
                    trace("request rows=${inputs.size} chars=${inputs.sumOf { it.cue.text.length }}")
                    val result = try {
                        translate(inputs.map { it.cue.text }, demand.config)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!owns(owner)) return
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    val lines = result.getOrNull()
                    if (lines != null && lines.size == inputs.size && lines.all { it.isNotBlank() }) {
                        val completed = inputs.indices.associate { index -> inputs[index].key to lines[index] }
                        translations.putAll(completed)
                        streamingPendingKeys.removeAll(completed.keys)
                        latest?.let(::publishTranslations)
                        dirtyCache = true
                        val saved = persistTranslations(demand)
                        cooldown.succeeded()
                        trace("success rows=${completed.size} persisted=$saved")
                        if (!saved) publishStatus(PodcastTranslationStatus.Error(saveErrorMessage()), owner)
                        else if (notify) publishStatus(PodcastTranslationStatus.Success, owner, hideAfterMs = 3_000)
                    } else {
                        val error = result.exceptionOrNull() as? TranslationRequestException
                        cooldown.failed(nowMs(), error?.httpStatusCode, error?.retryAfterMs)
                        trace("failure status=${error?.httpStatusCode ?: 0} retrySeconds=${(cooldown.retryAtMs - nowMs()).coerceAtLeast(0) / 1_000} suspended=${cooldown.requiresExplicitRetry}")
                        publishStatus(PodcastTranslationStatus.Error(errorMessage()), owner)
                    }
                    pendingSince = null
                }
            }
        }
    }

    private suspend fun persistTranslations(demand: Demand): Boolean {
        val cacheSnapshot = translations.toMap()
        val saved = withContext(NonCancellable) { store(demand.episodeId, demand.cacheKey, cacheSnapshot) }
        if (cacheLoaded && cacheIdentity == (demand.episodeId to demand.cacheKey)) dirtyCache = !saved
        currentCoroutineContext().ensureActive()
        return saved
    }

    private suspend fun awaitDemand(delayMs: Long) {
        withTimeoutOrNull(delayMs.coerceAtLeast(1)) { wakeups.receive() }
    }

    private fun owns(owner: Long): Boolean = owner == generation && latest != null

    private fun publishTranslations(demand: Demand) {
        onTranslations(buildMap {
            demand.cues.forEachIndexed { index, cue -> translations[cue.key]?.let { put(index, it) } }
        })
    }

    private fun publishStatus(status: PodcastTranslationStatus, owner: Long, hideAfterMs: Long? = null) {
        if (owner != generation) return
        statusJob?.cancel()
        currentStatus = status
        onStatus(status)
        statusJob = hideAfterMs?.let { duration ->
            scope.launch {
                delay(duration)
                if (owner == generation && currentStatus == status) {
                    currentStatus = PodcastTranslationStatus.Idle
                    onStatus(currentStatus)
                }
            }
        }
    }

    private companion object {
        const val MAX_RETAINED_COOLDOWNS = 16
        const val SEEK_PRIORITY_DISTANCE = 8
    }
}

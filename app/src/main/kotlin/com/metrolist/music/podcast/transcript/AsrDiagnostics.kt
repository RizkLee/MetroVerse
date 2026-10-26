/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.os.Debug
import android.os.SystemClock
import com.metrolist.music.BuildConfig
import timber.log.Timber

/** Debug-only aggregate measurements; never records audio, subtitle text, URLs or credentials. */
internal class AsrDiagnostics(private val model: AsrModel) {
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastReportAt = startedAt
    private var inferenceMs = 0L
    private var inferredSamples = 0L
    private var initialResumeMs = 0L

    fun addInference(elapsedMs: Long, audioSamples: Int = 0) {
        if (BuildConfig.DEBUG) {
            inferenceMs += elapsedMs
            inferredSamples += audioSamples
        }
    }

    fun resume(positionMs: Long, coveredMs: Long, retainedCues: Int, reason: String) {
        initialResumeMs = positionMs
        if (BuildConfig.DEBUG) Timber.tag("PodcastAsr").d(
            "stage=resume model=%s language=%s initialResumeMs=%d coveredMs=%d retainedCues=%d reason=%s",
            model.variant.pathName, model.language.code, positionMs, coveredMs, retainedCues, reason,
        )
    }

    fun seek(requestedMs: Long, actualMs: Long, fallback: Boolean) {
        if (BuildConfig.DEBUG) Timber.tag("PodcastAsr").d(
            "stage=seek requestedMs=%d actualMs=%d sequentialFallback=%b", requestedMs, actualMs, fallback,
        )
    }

    fun gap(fromMs: Long, toMs: Long) {
        if (BuildConfig.DEBUG) Timber.tag("PodcastAsr").d("stage=media_gap fromMs=%d toMs=%d", fromMs, toMs)
    }

    fun progress(audioMs: Long, cueCount: Int) {
        if (BuildConfig.DEBUG && SystemClock.elapsedRealtime() - lastReportAt >= 30_000) {
            record("progress", audioMs, cueCount)
        }
    }

    fun record(stage: String, audioMs: Long = 0L, cueCount: Int? = null) {
        if (!BuildConfig.DEBUG) return
        val runtime = Runtime.getRuntime()
        val now = SystemClock.elapsedRealtime()
        Timber.tag("PodcastAsr").d(
            "stage=%s model=%s language=%s elapsedMs=%d audioMs=%d initialResumeMs=%d inferredAudioMs=%d inferenceMs=%d%s javaKiB=%d nativeKiB=%d pssKiB=%d",
            stage,
            model.variant.pathName,
            model.language.code,
            now - startedAt,
            audioMs,
            initialResumeMs,
            inferredSamples / 16,
            inferenceMs,
            cueCount?.let { " cues=$it" }.orEmpty(),
            (runtime.totalMemory() - runtime.freeMemory()) / 1_024,
            Debug.getNativeHeapAllocatedSize() / 1_024,
            runCatching { Debug.getPss() }.getOrDefault(-1L),
        )
        lastReportAt = now
    }
}

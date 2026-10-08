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
internal class AsrDiagnostics {
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastReportAt = startedAt
    private var inferenceMs = 0L

    fun addInference(elapsedMs: Long) {
        if (BuildConfig.DEBUG) inferenceMs += elapsedMs
    }

    fun progress(audioMs: Long, cueCount: Int) {
        if (BuildConfig.DEBUG && SystemClock.elapsedRealtime() - lastReportAt >= 30_000) {
            record("progress", audioMs, cueCount)
        }
    }

    fun record(stage: String, audioMs: Long = 0L, cueCount: Int = 0) {
        if (!BuildConfig.DEBUG) return
        val runtime = Runtime.getRuntime()
        val now = SystemClock.elapsedRealtime()
        Timber.tag("PodcastAsr").d(
            "stage=%s elapsedMs=%d audioMs=%d inferenceMs=%d cues=%d javaKiB=%d nativeKiB=%d pssKiB=%d",
            stage,
            now - startedAt,
            audioMs,
            inferenceMs,
            cueCount,
            (runtime.totalMemory() - runtime.freeMemory()) / 1_024,
            Debug.getNativeHeapAllocatedSize() / 1_024,
            runCatching { Debug.getPss() }.getOrDefault(-1L),
        )
        lastReportAt = now
    }
}

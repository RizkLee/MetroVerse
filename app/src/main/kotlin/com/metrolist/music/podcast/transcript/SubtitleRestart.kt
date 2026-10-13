/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Cancellation checkpoints finish before deletion, even if the replacement request is cancelled. */
internal suspend fun clearSubtitlesAfterWriters(
    previousRecognition: Job?,
    awaitTranslations: suspend () -> Unit,
    isCurrent: () -> Boolean,
    clear: suspend () -> Result<Unit>,
): Result<Unit>? {
    withContext(NonCancellable) { previousRecognition?.join() }
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    awaitTranslations()
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    return clear()
}

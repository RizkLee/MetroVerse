/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.playback

/** Queue mode, sleep timers and autoplay own independent reasons to pause at a media boundary. */
internal fun shouldPauseAtMediaBoundary(
    autoplay: Boolean,
    sleepTimerRequestsPause: Boolean,
    queueRequestsPause: Boolean = false,
): Boolean = !autoplay || sleepTimerRequestsPause || queueRequestsPause

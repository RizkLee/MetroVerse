/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.playback

/** Sleep timers and autoplay own independent reasons to pause at a media boundary. */
internal fun shouldPauseAtMediaBoundary(autoplay: Boolean, sleepTimerRequestsPause: Boolean): Boolean =
    !autoplay || sleepTimerRequestsPause

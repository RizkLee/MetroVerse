/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember

private const val LYRICS_LINE_PEAK_SCALE = 1.025f

internal fun lyricsLineBreathingSpec(initialScale: Float) = keyframes<Float> {
    durationMillis = 750
    initialScale at 0 using FastOutSlowInEasing
    LYRICS_LINE_PEAK_SCALE at 125 using FastOutSlowInEasing
    1f at 750
}

internal fun boundedLyricsLineScale(
    animatedScale: Float,
    widthPx: Float,
    heightPx: Float,
    horizontalPaddingPx: Float,
    verticalPaddingPx: Float,
    pivotFractionX: Float,
): Float {
    if (widthPx <= 0f || heightPx <= 0f) return 1f
    val peakGrowth = LYRICS_LINE_PEAK_SCALE - 1f
    val horizontalExtent = widthPx * maxOf(pivotFractionX, 1f - pivotFractionX)
    val allowedGrowth = minOf(
        peakGrowth,
        horizontalPaddingPx / horizontalExtent,
        verticalPaddingPx / (heightPx * 0.5f),
    ).coerceAtLeast(0f)
    // Reduce the entire curve on wide or tall rows instead of clipping its peak or
    // changing text measurements. Both axes keep the same scale and glyph proportions.
    return 1f + (animatedScale - 1f).coerceIn(0f, peakGrowth) * (allowedGrowth / peakGrowth)
}

@Composable
internal fun rememberLyricsLineScale(
    isSynced: Boolean,
    isActiveLine: Boolean,
    isBackground: Boolean,
): State<Float> {
    val scale = remember { Animatable(1f) }
    val isHighlightedMainLine = isSynced && isActiveLine && !isBackground
    // Playback ticks and individual word boundaries must not restart the line's entrance.
    LaunchedEffect(isHighlightedMainLine) {
        if (isHighlightedMainLine) {
            scale.animateTo(1f, lyricsLineBreathingSpec(scale.value))
        } else if (scale.value != 1f) {
            scale.animateTo(1f, tween(150))
        }
    }
    return scale.asState()
}

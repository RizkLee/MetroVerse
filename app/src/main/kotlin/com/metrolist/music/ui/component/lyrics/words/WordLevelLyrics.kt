/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component.lyrics.words

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import com.metrolist.music.constants.LyricsAnimationStyle
import com.metrolist.music.lyrics.WordTimestamp

@Composable
internal fun WordLevelLyrics(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    adjustedEndTimeMs: Long?,
    lyricStyle: TextStyle,
    lineColor: () -> Color,
    expressiveAccent: Color,
    isBackground: Boolean,
    focusedAlpha: () -> Float,
    textMeasurer: TextMeasurer,
    isPlaying: Boolean = true,
    playbackSpeed: Float = 1f,
    animationStyle: LyricsAnimationStyle = LyricsAnimationStyle.APPLE,
    prewarmMasks: Boolean = isActiveLine,
) {
    when (animationStyle) {
        LyricsAnimationStyle.METROVERSE -> {
            MetroVerseWordAnimation(
                mainText = mainText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                focusedAlpha = focusedAlpha,
                textMeasurer = textMeasurer,
            )
        }

        LyricsAnimationStyle.APPLE -> {
            AppleWordAnimation(
                mainText = mainText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                adjustedEndTimeMs = adjustedEndTimeMs,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                isBackground = isBackground,
                focusedAlpha = focusedAlpha,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
                prewarmMasks = prewarmMasks,
            )
        }

        LyricsAnimationStyle.NONE -> {
            Text(
                text = mainText,
                style = lyricStyle.copy(color = if (isActiveLine) expressiveAccent else lineColor()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

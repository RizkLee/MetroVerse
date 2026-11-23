/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.constants.LyricsAnimationStyle
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.ui.component.lyrics.computeAppleDistanceScale
import com.metrolist.music.ui.component.lyrics.formatWrittenByAnnotated
import com.metrolist.music.ui.component.lyrics.words.WordLevelLyrics
import com.metrolist.music.ui.component.lyrics.words.isJoiningScript
import com.metrolist.music.ui.component.lyrics.words.isRtlText
import com.metrolist.music.ui.screens.settings.LyricsPosition
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun splitLyricsHighlightWords(words: List<WordTimestamp>): List<WordTimestamp> =
    words.flatMap { word ->
        val shouldSplit = word.text.contains('-') && word.text.length > 1 &&
            (!word.hasTrailingSpace || words.size == 1)
        if (!shouldSplit) return@flatMap listOf(word)

        val segments = mutableListOf<String>()
        var start = 0
        for (index in word.text.indices) {
            if (word.text[index] == '-') {
                segments.add(word.text.substring(start, index + 1))
                start = index + 1
            }
        }
        if (start < word.text.length) {
            segments.add(word.text.substring(start))
        }

        if (segments.size <= 1) {
            listOf(word)
        } else {
            val segmentDuration = (word.endTime - word.startTime) / segments.size
            segments.mapIndexed { index, segmentText ->
                WordTimestamp(
                    text = segmentText,
                    startTime = word.startTime + index * segmentDuration,
                    endTime = word.startTime + (index + 1) * segmentDuration,
                    hasTrailingSpace = index == segments.lastIndex && word.hasTrailingSpace,
                )
            }
        }
    }

@Composable
private fun AlphaSafeLyricText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val needsOffscreen =
        remember(text) { isJoiningScript(text) } && color.alpha > 0f && color.alpha < 0.999f
    if (needsOffscreen) {
        val layerAlpha = color.alpha
        Text(
            text = text,
            style = style.copy(color = color.copy(alpha = 1f)),
            modifier =
                modifier.graphicsLayer {
                    alpha = layerAlpha
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        )
    } else {
        Text(
            text = text,
            style = style.copy(color = color),
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsLine(
    index: Int,
    item: LyricsEntry,
    isSynced: Boolean,
    isActiveLine: Boolean,
    bgVisible: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    positionProvider: () -> Long,
    adjustedEndTimeMs: Long?,
    isPlaying: Boolean,
    playbackSpeed: Float,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    animationStyle: LyricsAnimationStyle,
    progressiveBlurDp: Float,
    distance: Int,
    expressiveAccent: Color,
    textMeasurer: TextMeasurer,
    lyricsTextPosition: LyricsPosition,
    respectAgentPositioning: Boolean,
    isAutoScrollEnabled: Boolean,
    displayedCurrentLineIndex: Int,
    romanizeAsMain: Boolean,
    enabledLanguages: List<String>,
    romanizeLyrics: Boolean,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metroVerseBreathScale =
        if (animationStyle == LyricsAnimationStyle.METROVERSE) {
            rememberLyricsLineScale(isSynced, isActiveLine, item.isBackground)
        } else {
            null
        }
    val targetAppleScale =
        if (animationStyle == LyricsAnimationStyle.APPLE && !item.isBackground) {
            computeAppleDistanceScale(
                distance = distance,
                isActive = isActiveLine,
                isSynced = isSynced,
                isAutoScrollEnabled = isAutoScrollEnabled,
                isSelectionMode = isSelectionModeActive,
            )
        } else {
            1f
        }
    val appleDistanceScale by animateFloatAsState(
        targetValue = targetAppleScale,
        animationSpec = tween(durationMillis = 350),
        label = "appleDistanceScale",
    )
    val animatedBlurDp by animateFloatAsState(
        targetValue = progressiveBlurDp,
        animationSpec = tween(durationMillis = 300),
        label = "lyricsLineBlur",
    )

    val horizontalPadding =
        when (lyricsTextPosition) {
            LyricsPosition.LEFT, LyricsPosition.RIGHT -> 12.dp
            LyricsPosition.CENTER -> 24.dp
        }

    val itemModifier =
        modifier
            .fillMaxWidth()
            .onSizeChanged { onSizeChanged(it.height) }
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ).background(
                if (isSelected && isSelectionModeActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                },
            ).padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = if (item.isBackground) 0.dp else 12.dp,
                bottom = if (item.isBackground) 2.dp else 12.dp,
            )

    val agentAlignment =
        when {
            respectAgentPositioning && item.agent == "v1" -> Alignment.Start
            respectAgentPositioning && item.agent == "v2" -> Alignment.End
            respectAgentPositioning && item.agent == "v1000" -> Alignment.CenterHorizontally
            item.isBackground -> Alignment.CenterHorizontally
            else ->
                when (lyricsTextPosition) {
                    LyricsPosition.LEFT -> Alignment.Start
                    LyricsPosition.CENTER -> Alignment.CenterHorizontally
                    LyricsPosition.RIGHT -> Alignment.End
                }
        }

    val agentTextAlign =
        when {
            respectAgentPositioning && item.agent == "v1" -> TextAlign.Left
            respectAgentPositioning && item.agent == "v2" -> TextAlign.Right
            respectAgentPositioning && item.agent == "v1000" -> TextAlign.Center
            item.isBackground -> TextAlign.Center
            else ->
                when (lyricsTextPosition) {
                    LyricsPosition.LEFT -> TextAlign.Left
                    LyricsPosition.CENTER -> TextAlign.Center
                    LyricsPosition.RIGHT -> TextAlign.Right
                }
        }

    val boxAlignment =
        when {
            respectAgentPositioning && item.agent == "v1" -> Alignment.CenterStart
            respectAgentPositioning && item.agent == "v2" -> Alignment.CenterEnd
            item.isBackground -> Alignment.Center
            respectAgentPositioning && item.agent == "v1000" -> Alignment.Center
            else ->
                when (lyricsTextPosition) {
                    LyricsPosition.LEFT -> Alignment.CenterStart
                    LyricsPosition.RIGHT -> Alignment.CenterEnd
                    LyricsPosition.CENTER -> Alignment.Center
                }
        }

    Box(modifier = itemModifier, contentAlignment = boxAlignment) {
        @Composable
        fun LyricContent() {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            renderEffect =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && animatedBlurDp > 0.25f) {
                                    val rawPx = animatedBlurDp.dp.toPx()
                                    val quantizedPx = (rawPx * 4f).roundToInt() / 4f
                                    if (quantizedPx > 0.25f) BlurEffect(quantizedPx, quantizedPx, TileMode.Decal) else null
                                } else {
                                    null
                                }
                        },
                horizontalAlignment = agentAlignment,
            ) {
                val isApple = animationStyle == LyricsAnimationStyle.APPLE
                val activeAlpha = 1f
                val focusedAlpha =
                    when {
                        item.isBackground -> 0.5f
                        isApple -> 0.35f
                        else -> 0.3f
                    }
                val inactiveAlpha =
                    when {
                        isSelectionModeActive -> 0.5f
                        item.isBackground -> 0.08f
                        isApple -> 0.35f
                        else -> 0.2f
                    }
                val targetAlpha =
                    if (!isSynced || item.isBackground || isActiveLine) {
                        activeAlpha
                    } else if (isAutoScrollEnabled && displayedCurrentLineIndex >= 0) {
                        if (isApple) {
                            0.35f
                        } else {
                            when (abs(index - displayedCurrentLineIndex)) {
                                0 -> focusedAlpha
                                1, 2 -> 0.2f
                                3 -> 0.15f
                                4 -> 0.1f
                                else -> 0.08f
                            }
                        }
                    } else {
                        inactiveAlpha
                    }

                val animatedAlpha by animateFloatAsState(targetAlpha, tween(250), label = "lyricsLineAlpha")
                val lineColor = expressiveAccent.copy(alpha = if (item.isBackground) focusedAlpha else animatedAlpha)

                val romanizedTextState by item.romanizedTextFlow.collectAsStateWithLifecycle()
                val isRomanizedAvailable = romanizedTextState != null
                val mainTextRaw = if (romanizeAsMain && isRomanizedAvailable) romanizedTextState else item.text
                val subTextRaw = if (romanizeAsMain && isRomanizedAvailable) item.text else romanizedTextState
                val mainText =
                    if (item.isBackground) mainTextRaw?.removePrefix("(")?.removeSuffix(")") else mainTextRaw
                val subText =
                    if (item.isBackground) subTextRaw?.removePrefix("(")?.removeSuffix(")") else subTextRaw
                val safeMainText = mainText.orEmpty()
                val isJoiningMain = remember(safeMainText) { isJoiningScript(safeMainText) }
                val isRtlMain = remember(safeMainText) { isRtlText(safeMainText) }

                val fontSize = if (item.isBackground) lyricsTextSize * 0.7f else lyricsTextSize
                val lyricStyle =
                    remember(
                        fontSize,
                        lyricsLineSpacing,
                        item.isBackground,
                        agentTextAlign,
                        isJoiningMain,
                        isRtlMain,
                    ) {
                        TextStyle(
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.Bold,
                            fontStyle = if (item.isBackground) FontStyle.Italic else FontStyle.Normal,
                            lineHeight = (fontSize * lyricsLineSpacing).sp,
                            letterSpacing = if (isJoiningMain) 0.sp else (-0.5).sp,
                            textAlign = agentTextAlign,
                            textDirection = if (isRtlMain) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr,
                            fontFeatureSettings = "liga 0",
                            textMotion = TextMotion.Animated,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle =
                                LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                        )
                    }.let { base ->
                        base.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily)
                    }

                val effectiveWords =
                    remember(item.words, item.isBackground) {
                        item.words
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { raw ->
                                if (!item.isBackground) {
                                    raw
                                } else {
                                    raw.mapIndexed { idx, w ->
                                        var t = w.text
                                        if (idx == 0) t = t.removePrefix("(")
                                        if (idx == raw.lastIndex) t = t.removeSuffix(")")
                                        w.copy(text = t)
                                    }
                                }
                            }
                    }

                val showWordLevel =
                    isSynced &&
                        !(romanizeAsMain && isRomanizedAvailable) &&
                        animationStyle != LyricsAnimationStyle.NONE &&
                        effectiveWords != null &&
                        (isActiveLine || abs(index - displayedCurrentLineIndex) <= 1) &&
                        safeMainText.isNotEmpty()

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val horizontalPivot =
                                    when (agentTextAlign) {
                                        TextAlign.Left -> 0f
                                        TextAlign.Right -> 1f
                                        else -> 0.5f
                                    }
                                val finalScale =
                                    if (metroVerseBreathScale != null) {
                                        boundedLyricsLineScale(
                                            animatedScale = metroVerseBreathScale.value,
                                            widthPx = size.width,
                                            heightPx = size.height,
                                            horizontalPaddingPx = horizontalPadding.toPx(),
                                            verticalPaddingPx = 2.dp.toPx(),
                                            pivotFractionX = horizontalPivot,
                                        )
                                    } else {
                                        appleDistanceScale
                                    }
                                scaleX = finalScale
                                scaleY = finalScale
                                transformOrigin =
                                    TransformOrigin(
                                        pivotFractionX = horizontalPivot,
                                        pivotFractionY = 0.5f,
                                    )
                                clip = false
                            },
                ) {
                    if (showWordLevel) {
                        WordLevelLyrics(
                            mainText = safeMainText,
                            words = effectiveWords,
                            isActiveLine = isActiveLine,
                            positionProvider = positionProvider,
                            adjustedEndTimeMs = adjustedEndTimeMs,
                            lyricStyle = lyricStyle,
                            lineColor = { lineColor },
                            expressiveAccent = expressiveAccent,
                            isBackground = item.isBackground,
                            focusedAlpha = { focusedAlpha },
                            textMeasurer = textMeasurer,
                            isPlaying = isPlaying,
                            playbackSpeed = playbackSpeed,
                            animationStyle = animationStyle,
                            prewarmMasks = isActiveLine || distance <= 1,
                        )
                    } else {
                        val inactiveColor =
                            if (isActiveLine) expressiveAccent else lineColor
                        AlphaSafeLyricText(
                            text = safeMainText,
                            style = lyricStyle,
                            color = inactiveColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (romanizeLyrics && enabledLanguages.isNotEmpty()) {
                    subText?.takeIf { it.isNotBlank() }?.let { rom ->
                        val romSize = (fontSize * 0.54f).coerceIn(12f, 24f)
                        val romStyle =
                            TextStyle(
                                fontSize = romSize.sp,
                                lineHeight = (romSize * lyricsLineSpacing).sp,
                                textAlign = agentTextAlign,
                                fontWeight = FontWeight.Normal,
                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                            )
                        AlphaSafeLyricText(
                            text = rom,
                            style = romStyle,
                            color = expressiveAccent.copy(alpha = if (isActiveLine) 0.72f else 0.45f),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                        )
                    }
                }

                val transText by item.translatedTextFlow.collectAsStateWithLifecycle()
                transText?.takeIf { it.isNotBlank() }?.let { trans ->
                    val transSize = (fontSize * 0.5f).coerceIn(12f, 22f)
                    val transStyle =
                        TextStyle(
                            fontSize = transSize.sp,
                            lineHeight = (transSize * lyricsLineSpacing).sp,
                            textAlign = agentTextAlign,
                            fontWeight = FontWeight.Normal,
                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                        )
                    AlphaSafeLyricText(
                        text = trans,
                        style = transStyle,
                        color = expressiveAccent.copy(alpha = if (isActiveLine) 0.65f else 0.35f),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                    )
                }
            }
        }

        if (item.isBackground) {
            AnimatedVisibility(
                visible = bgVisible,
                enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
                exit = fadeOut(tween(250)),
            ) {
                LyricContent()
            }
        } else {
            LyricContent()
        }
    }
}

/**
 * Unblurred footer displaying songwriter credits and lyrics provider at the end of the lyrics list.
 * Must NEVER receive progressive blur so end-of-song attribution always stays crisp.
 */
@Composable
internal fun LyricsAttributionFooter(
    provider: String?,
    songwriters: List<String>,
    textColor: Color,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    val validSongwriters = remember(songwriters) { songwriters.map(String::trim).filter(String::isNotEmpty).distinct() }
    val validProvider =
        remember(provider) {
            provider?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) && !it.equals("Manual", ignoreCase = true)
            }
        }
    if (validSongwriters.isEmpty() && validProvider == null) return

    val creditColor = textColor.copy(alpha = 0.65f)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 36.dp),
    ) {
        if (validSongwriters.isNotEmpty()) {
            val writtenTemplate = stringResource(R.string.lyrics_written_by, validSongwriters.joinToString(", "))
            Text(
                text = formatWrittenByAnnotated(writtenTemplate, validSongwriters),
                style = MaterialTheme.typography.labelLarge,
                color = creditColor,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (validProvider != null) {
            Text(
                text = stringResource(R.string.lyrics_from_provider, validProvider),
                style = MaterialTheme.typography.labelMedium,
                color = creditColor,
                textAlign = textAlign,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (validSongwriters.isNotEmpty()) 4.dp else 0.dp),
            )
        }
    }
}

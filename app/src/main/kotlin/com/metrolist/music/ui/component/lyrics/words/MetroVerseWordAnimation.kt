/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component.lyrics.words

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.ui.component.splitLyricsHighlightWords
import kotlin.math.abs

private class MetroWordRun(
    val sylIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val charLen: Int,
    val isRtl: Boolean,
    val isJoiningScript: Boolean,
    val vc: VisualCluster,
    val x: Float,
    val y: Float,
    val width: Float,
)

/**
 * Clean GPU-accelerated implementation of the signature MetroVerse word-by-word highlight:
 * - Preserves full-line font shaping, kerning, ligatures, RTL, and Indic/Arabic joining scripts.
 * - Replaces the legacy 12-pass `clipRect` loop and per-character `BlurMaskFilter` / `Typeface`
 *   allocations with a single linear-gradient fill sweep and cached `GlowShadowTable` halo per word.
 * - Reads the shared playback position inside DrawScope with zero per-row coroutine polling.
 */
@Composable
internal fun MetroVerseWordAnimation(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    lyricStyle: TextStyle,
    lineColor: () -> Color,
    expressiveAccent: Color,
    focusedAlpha: () -> Float,
    textMeasurer: TextMeasurer,
) {
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val density = LocalDensity.current
    val fontSizePx = remember(lyricStyle) { with(density) { lyricStyle.fontSize.toPx() } }
    val extraPaddingPx = minOf(fontSizePx * 0.3f, with(density) { LYRICS_WORD_CANVAS_PADDING.toPx() })
    val maxGlowBlurPx = with(density) { 12f * density.density }

    val effectiveWords = remember(words) { splitLyricsHighlightWords(words) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        if (widthPx <= 0) return@BoxWithConstraints

        val textLayoutResult =
            remember(mainText, lyricStyle, widthPx) {
                textMeasurer.measure(
                    text = AnnotatedString(mainText),
                    style = lyricStyle,
                    softWrap = true,
                    constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
                )
            }

        val metrics = remember(textLayoutResult) { WordLineMetrics(textLayoutResult) }
        val lineIsRtl = metrics.lineIsRtl
        val lineBaselines = metrics.lineBaselines

        val alignedSyllables =
            remember(effectiveWords, mainText) { alignSyllablesForFormatting(effectiveWords, mainText) }

        val runs =
            remember(mainText, textLayoutResult, alignedSyllables, lyricStyle, expressiveAccent) {
                val metaList = buildInitialClusterMetas(mainText, textLayoutResult, lineIsRtl)
                val reconstructedWords = reconstructWords(alignedSyllables, mainText)
                for ((wordIdx, word) in reconstructedWords.withIndex()) {
                    val wordText = word.syllables.joinToString("") { it.syllable.text }
                    if (isJoiningScript(wordText)) {
                        for (m in metasInRange(metaList, word.startIndex, word.endIndex)) {
                            if (!mainText[m.start].isWhitespace()) {
                                m.isJoiningScript = true
                                m.wordIndex = wordIdx
                            }
                        }
                    }
                }

                val clusters =
                    buildVisualClusters(
                        metaList = metaList,
                        alignedSyllables = alignedSyllables,
                        formattedMainText = mainText,
                        lineBaselines = lineBaselines,
                        lyricStyle = lyricStyle,
                        expressiveAccent = expressiveAccent,
                        textMeasurer = textMeasurer,
                    )

                clusters.map { vc ->
                    val syl = alignedSyllables.getOrNull(vc.syllableIndex)?.syllable
                    val startMs = syl?.let { (it.startTime * 1000).toLong() } ?: Long.MAX_VALUE
                    val endMs =
                        syl?.let {
                            val rawEnd = (it.endTime * 1000).toLong()
                            maxOf(startMs + 100L, rawEnd)
                        } ?: startMs
                    val charLen = (syl?.text?.length ?: (vc.end - vc.start)).coerceAtLeast(1)
                    MetroWordRun(
                        sylIndex = vc.syllableIndex,
                        startMs = startMs,
                        endMs = endMs,
                        charLen = charLen,
                        isRtl = vc.isRtl || lineIsRtl.getOrElse(vc.lineIdx) { false },
                        isJoiningScript = vc.isJoiningScript,
                        vc = vc,
                        x = vc.x,
                        y = vc.y,
                        width = vc.width,
                    )
                }
            }

        val isInactiveJoining = remember(mainText) { isJoiningScript(mainText) }
        val glowShadowTable = remember(maxGlowBlurPx, expressiveAccent) {
            GlowShadowTable(blurRadius = maxGlowBlurPx, baseColor = expressiveAccent)
        }
        val transparentBrush = remember(expressiveAccent) { SolidColor(expressiveAccent.copy(alpha = 0f)) }
        val colorCache = remember { ColorAlphaCache() }
        val brushCache = remember { SolidColorCache() }
        val inactiveLineCluster =
            remember(mainText, textLayoutResult, isInactiveJoining) {
                VisualCluster(
                    start = 0,
                    end = mainText.length,
                    isJoiningScript = isInactiveJoining,
                    subLayout = textLayoutResult,
                )
            }

        Canvas(
            modifier =
                Modifier
                    .lyricsWordCanvasLayout(
                        mainText = mainText,
                        textLayoutResult = textLayoutResult,
                        extraPaddingPx = extraPaddingPx,
                    ).graphicsLayer {
                        clip = false
                    },
        ) {
            if (mainText.isEmpty()) return@Canvas

            if (!isActiveLine) {
                val inactiveBrush = brushCache.get(lineColor())
                drawClusterText(
                    vc = inactiveLineCluster,
                    topLeft = Offset(extraPaddingPx, extraPaddingPx),
                    brush = inactiveBrush,
                    shadow = LyricsClearShadow,
                )
                return@Canvas
            }

            val pos = currentPositionProvider()
            val fAlpha = focusedAlpha().coerceIn(0f, 1f)
            val fullColor = colorCache.get(expressiveAccent, 1f)
            val fullBrush = brushCache.get(fullColor)
            val dimBrush = SolidColor(expressiveAccent.copy(alpha = fAlpha))

            for (i in runs.indices) {
                val run = runs[i]
                val topLeft = Offset(run.x + extraPaddingPx, run.y + extraPaddingPx)
                if (run.sylIndex == -1) {
                    drawClusterText(run.vc, topLeft, dimBrush, LyricsClearShadow)
                    continue
                }

                val durMs = (run.endMs - run.startMs).coerceAtLeast(100L)
                val isSung = pos > run.endMs
                val isActiveWord = pos in run.startMs..run.endMs

                if (isSung) {
                    drawClusterText(run.vc, topLeft, fullBrush, LyricsClearShadow)
                    continue
                }

                if (!isActiveWord) {
                    drawClusterText(run.vc, topLeft, dimBrush, LyricsClearShadow)
                    continue
                }

                val wordProgress = ((pos - run.startMs).toFloat() / durMs.toFloat()).coerceIn(0f, 1f)

                // Preserve MetroVerse's subtle halo glow on held syllables using prebuilt Shadow buckets.
                val durFloat = durMs.toFloat()
                val impactRatio = durFloat / run.charLen
                val fadeFactor = (wordProgress * 5f).coerceIn(0f, 1f) * ((1f - wordProgress) * 8f).coerceIn(0f, 1f)
                val impactFactor =
                    (
                        ((impactRatio - 100f) / 250f).coerceIn(0f, 1f) * 0.6f +
                            ((durFloat - 300f) / 1500f).coerceIn(0f, 1f) * 0.4f
                    ).coerceIn(0f, 1f) * fadeFactor
                if (impactFactor > 0.01f && !run.isJoiningScript) {
                    val glowAlpha = (0.35f * impactFactor).coerceIn(0f, 0.4f)
                    drawText(
                        textLayoutResult = run.vc.subLayout,
                        topLeft = topLeft,
                        brush = transparentBrush,
                        shadow = glowShadowTable.get(glowAlpha),
                    )
                }

                val trailingAlpha = (fAlpha + (1f - fAlpha) * wordProgress).coerceIn(0f, 1f)
                val trailingColor = expressiveAccent.copy(alpha = trailingAlpha)
                val spanWidth = maxOf(1f, run.vc.subLayout.size.width.toFloat())
                val edgeWidth = (spanWidth * 0.45f).coerceIn(1f, fontSizePx * 1.2f)
                val sweepBrush =
                    if (run.isRtl) {
                        val frontX = spanWidth - (spanWidth + edgeWidth * 0.35f) * wordProgress
                        val softStartX = (frontX + edgeWidth).coerceAtMost(spanWidth)
                        if (abs(softStartX - frontX) < 0.5f) {
                            SolidColor(trailingColor)
                        } else {
                            Brush.linearGradient(
                                0f to fullColor,
                                1f to trailingColor,
                                start = Offset(softStartX, 0f),
                                end = Offset(frontX, 0f),
                            )
                        }
                    } else {
                        val frontX = (spanWidth + edgeWidth * 0.35f) * wordProgress
                        val softStartX = (frontX - edgeWidth).coerceAtLeast(0f)
                        if (abs(frontX - softStartX) < 0.5f) {
                            SolidColor(trailingColor)
                        } else {
                            Brush.linearGradient(
                                0f to fullColor,
                                1f to trailingColor,
                                start = Offset(softStartX, 0f),
                                end = Offset(frontX, 0f),
                            )
                        }
                    }
                if (run.isJoiningScript) {
                    drawClusterBandText(
                        vc = run.vc,
                        topLeft = topLeft,
                        bandBrush = sweepBrush,
                        unlitColor = trailingColor,
                        shadow = LyricsClearShadow,
                    )
                } else {
                    drawText(
                        textLayoutResult = run.vc.subLayout,
                        topLeft = topLeft,
                        brush = sweepBrush,
                        shadow = LyricsClearShadow,
                    )
                }
            }
        }
    }
}

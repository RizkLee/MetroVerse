/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil

internal object LyricsShareLayout {
    const val IMAGE_WIDTH = 2560
    const val IMAGE_HEIGHT = 2160
    const val REFERENCE_HEIGHT = 340f
    const val CONTENT_PADDING = 24f
    const val COVER_SIZE = 52f
    const val HEADER_GAP = 12f
    const val TITLE_MAX_LINES = 3
    const val MIN_LYRICS_TEXT_SIZE = 13f
    private const val MAX_IMAGE_HEIGHT = 8192

    fun imageHeightFor(title: String, artist: String, lyrics: String): Int {
        val scale = IMAGE_HEIGHT / REFERENCE_HEIGHT
        val padding = CONTENT_PADDING * scale
        val titleWidth = (IMAGE_WIDTH - padding * 2 - (COVER_SIZE + HEADER_GAP) * scale).toInt()
        val titleHeight = measureText(title, titleWidth, 18f * scale, bold = true, maxLines = TITLE_MAX_LINES).height
        val artistHeight = measureText(artist, titleWidth, 14f * scale, bold = false, maxLines = 1).height
        val headerHeight = maxOf(COVER_SIZE * scale, titleHeight + artistHeight + 2f * scale)
        val lyricsHeight = measureText(
            lyrics,
            (IMAGE_WIDTH - padding * 2).toInt(),
            MIN_LYRICS_TEXT_SIZE * scale,
            bold = true,
            lyrics = true,
        ).height
        val requiredHeight = padding * 2 + headerHeight + lyricsHeight + (12f + 12f + 22f) * scale
        // Bound bitmap allocation for unusually large manually supplied lyric lines.
        return ceil(requiredHeight).toInt().coerceIn(IMAGE_HEIGHT, MAX_IMAGE_HEIGHT)
    }

    private fun measureText(
        text: String,
        width: Int,
        size: Float,
        bold: Boolean,
        maxLines: Int = Int.MAX_VALUE,
        lyrics: Boolean = false,
    ): StaticLayout {
        val paint = TextPaint().apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isAntiAlias = true
            if (lyrics) letterSpacing = 0.005f
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .apply {
                if (lyrics) {
                    setLineSpacing(0f, 1.2f)
                    setIncludePad(false)
                } else {
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
            }
            .build()
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import java.util.Locale

enum class AsrLanguage(val code: String, val nativeName: String, val modelDate: String) {
    ENGLISH("en", "English", "26_08_21"),
    GERMAN("de", "Deutsch", "26_08_24"),
    SPANISH("es", "Español", "26_08_24"),
    JAPANESE("ja", "日本語", "26_08_23");

    val directory: String get() = "moonshine-small-streaming-$code-${modelDate.replace('_', '-')}"
    val checkpointIdentity: String get() = "moonshine-small-streaming-$code-quantized_$modelDate-v1"

    companion object {
        fun fromCode(code: String?): AsrLanguage? = entries.firstOrNull { it.code == normalizeAsrLanguage(code) }
    }
}

internal fun normalizeAsrLanguage(value: String?): String? = value?.trim()?.replace('_', '-')
    ?.lowercase(Locale.ROOT)?.substringBefore('-')?.takeIf { it.isNotBlank() }

internal fun resolveAsrLanguage(episodeOverride: String?, channelOverride: String?, rssLanguage: String?): String =
    normalizeAsrLanguage(episodeOverride) ?: normalizeAsrLanguage(channelOverride) ?: normalizeAsrLanguage(rssLanguage) ?: "en"

data class AsrLanguageSelection(
    val code: String? = null,
    val channelName: String = "",
    val channelId: String? = null,
    val channelDefault: String? = null,
) {
    val language: AsrLanguage? get() = AsrLanguage.fromCode(code)
    val isSupported: Boolean get() = language != null
}

/** One retry per attempt; only healthy inference progress, not buffering time, rearms it. */
internal class AsrAutomaticRetry {
    private var used = false
    private var healthySinceMs: Long? = null
    private var lastProgressMs: Long? = null

    fun madeProgress(nowMs: Long) {
        val previous = lastProgressMs
        if (previous == null || nowMs - previous > 30_000) healthySinceMs = nowMs
        lastProgressMs = nowMs
        if (used && nowMs - checkNotNull(healthySinceMs) > 120_000) used = false
    }

    fun retry(): Boolean {
        if (used) return false
        used = true
        healthySinceMs = null
        lastProgressMs = null
        return true
    }
}

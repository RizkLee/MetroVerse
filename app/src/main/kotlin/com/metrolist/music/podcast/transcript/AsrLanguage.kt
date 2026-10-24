/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import java.util.Locale

enum class AsrModelVariant(val pathName: String, val nativeArchitecture: Int, val displayName: String) {
    SMALL_STREAMING("small-streaming", 4, "Moonshine Small Streaming"),
    TINY_STREAMING("tiny-streaming", 2, "Moonshine Tiny Streaming"),
}

enum class AsrLanguage(
    val code: String,
    val nativeName: String,
    val modelDate: String,
    val modelVariant: AsrModelVariant = AsrModelVariant.SMALL_STREAMING,
) {
    ENGLISH("en", "English", "26_08_21"),
    GERMAN("de", "Deutsch", "26_08_24"),
    SPANISH("es", "Español", "26_08_24"),
    JAPANESE("ja", "日本語", "26_08_23"),
    MANDARIN("zh", "普通话", "26_08_24", AsrModelVariant.TINY_STREAMING),
    ARABIC("ar", "العربية", "26_08_24", AsrModelVariant.TINY_STREAMING),
    TAGALOG("tl", "Tagalog", "26_08_24", AsrModelVariant.TINY_STREAMING),
    VIETNAMESE("vi", "Tiếng Việt", "26_08_24", AsrModelVariant.TINY_STREAMING);

    val directory: String get() = "moonshine-${modelVariant.pathName}-$code-${modelDate.replace('_', '-')}"
    val checkpointIdentity: String get() = "moonshine-${modelVariant.pathName}-$code-quantized_$modelDate-v1"

    companion object {
        fun fromCode(code: String?): AsrLanguage? {
            val normalized = normalizeAsrLanguage(code) ?: return null
            return entries.firstOrNull { it.code == normalized }
        }
    }
}

internal fun normalizeAsrLanguage(value: String?): String? {
    val parts = value?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)?.split('-') ?: return null
    val base = parts.first().takeIf { it.isNotBlank() } ?: return null
    // Chinese extlang tags for other spoken languages must not select the Mandarin model.
    if (base == "zh" && parts.getOrNull(1) in setOf("yue", "hak", "nan", "wuu", "gan")) return parts[1]
    return when (base) {
        "cmn", "zho", "chi" -> "zh"
        "fil", "tgl" -> "tl"
        "ara", "arb" -> "ar"
        "vie" -> "vi"
        else -> base
    }
}

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

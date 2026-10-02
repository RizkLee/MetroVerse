/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.api.DeepLService
import com.metrolist.music.api.MistralService
import com.metrolist.music.api.OpenRouterService

/**
 * Translation configuration resolved from the same DataStore keys that power AI lyrics
 * translation, so configuring one feature configures both.
 */
data class SubtitleTranslationConfig(
    val provider: String,
    val apiKey: String,
    val deeplApiKey: String,
    val deeplFormality: String,
    val baseUrl: String,
    val model: String,
    val mode: String,
    val targetLanguage: String,
    val systemPrompt: String,
) {
    val hasCredentials: Boolean
        get() = if (provider == "DeepL") deeplApiKey.isNotBlank() else apiKey.isNotBlank()
}

/**
 * Translates podcast subtitle lines with the same AI providers as lyrics translation.
 *
 * Lines are sent as one batch (newline separated) and mapped back by index, matching the
 * contract of [DeepLService]/[MistralService]/[OpenRouterService].
 */
object SubtitleTranslationHelper {

    /**
     * Translates [lines] in one request. Returns translations aligned with the input list;
     * blank input lines come back as blank strings.
     */
    suspend fun translateLines(
        lines: List<String>,
        config: SubtitleTranslationConfig,
        context: Context,
    ): Result<List<String>> {
        if (lines.isEmpty()) return Result.success(emptyList())
        if (!config.hasCredentials) {
            return Result.failure(IllegalStateException(context.getString(R.string.ai_error_api_key_required)))
        }

        val nonEmpty = lines.mapIndexedNotNull { index, line ->
            if (line.isNotBlank()) index else null
        }
        if (nonEmpty.isEmpty()) {
            return Result.success(lines.map { "" })
        }

        val fullText = nonEmpty.joinToString("\n") { lines[it] }
        val result = when (config.provider) {
            "DeepL" -> DeepLService.translate(
                text = fullText,
                targetLanguage = config.targetLanguage,
                apiKey = config.deeplApiKey,
                formality = config.deeplFormality,
            )

            "Mistral" -> MistralService.translate(
                text = fullText,
                targetLanguage = displayLanguageName(config.targetLanguage),
                apiKey = config.apiKey,
                model = config.model,
                mode = config.mode,
                customSystemPrompt = config.systemPrompt,
            )

            else -> OpenRouterService.translate(
                text = fullText,
                targetLanguage = displayLanguageName(config.targetLanguage),
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                model = config.model,
                mode = config.mode,
                customSystemPrompt = config.systemPrompt,
            )
        }

        return result.map { translatedLines ->
            val byIndex = mutableMapOf<Int, String>()
            translatedLines.forEachIndexed { position, text ->
                if (position < nonEmpty.size) {
                    byIndex[nonEmpty[position]] = text
                }
            }
            lines.mapIndexed { index, line -> if (line.isBlank()) "" else byIndex[index].orEmpty() }
        }
    }

    private fun displayLanguageName(code: String): String =
        runCatching { java.util.Locale.forLanguageTag(code).displayLanguage }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != code }
            ?: code
}

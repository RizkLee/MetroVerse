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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
        currentCoroutineContext().ensureActive()
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
                maxRetries = 1,
                strictLineCount = true,
            )

            "Mistral" -> MistralService.translate(
                text = fullText,
                targetLanguage = displayLanguageName(config.targetLanguage),
                apiKey = config.apiKey,
                model = config.model,
                mode = config.mode,
                customSystemPrompt = config.systemPrompt,
                maxRetries = 1,
                strictLineCount = true,
            )

            else -> OpenRouterService.translate(
                text = fullText,
                targetLanguage = displayLanguageName(config.targetLanguage),
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                model = config.model,
                mode = config.mode,
                customSystemPrompt = config.systemPrompt,
                maxRetries = 1,
                strictLineCount = true,
            )
        }

        currentCoroutineContext().ensureActive()
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result.mapCatching { translatedLines ->
            alignTranslations(lines, translatedLines)
        }
    }

    internal fun alignTranslations(lines: List<String>, translatedLines: List<String>): List<String> {
        val nonEmpty = lines.indices.filter { lines[it].isNotBlank() }
        require(translatedLines.size == nonEmpty.size) { "Translation line count mismatch" }
        require(translatedLines.all { it.isNotBlank() }) { "Translation contains an empty line" }
        val byIndex = nonEmpty.zip(translatedLines).toMap()
        return lines.indices.map { byIndex[it].orEmpty() }
    }

    private fun displayLanguageName(code: String): String =
        runCatching { java.util.Locale.forLanguageTag(code).displayLanguage }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != code }
            ?: code
}

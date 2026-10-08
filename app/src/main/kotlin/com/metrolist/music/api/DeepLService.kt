/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DeepLService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String,
        formality: String = "default",
        maxRetries: Int = 3,
        strictLineCount: Boolean = false,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        var currentAttempt = 0
        var lastFailure: TranslationRequestException? = null
        
        // Validate input
        if (text.isBlank()) {
            return@withContext Result.failure(Exception("Input text is empty"))
        }
        
        val lines = text.lines()
        val lineCount = lines.size
        
        // DeepL language codes (uppercase)
        val deeplLangCode = when (targetLanguage.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "ZH"
            "zh-tw", "zh-hant" -> "ZH"
            "en", "en-us" -> "EN-US"
            "en-gb" -> "EN-GB"
            "pt", "pt-pt" -> "PT-PT"
            "pt-br" -> "PT-BR"
            else -> targetLanguage.uppercase().take(2)
        }
        
        // Determine if using free or pro API
        val baseUrl = if (apiKey.endsWith(":fx")) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }
        
        while (currentAttempt < maxRetries) {
            try {
                val jsonBody = JSONObject().apply {
                    put("text", JSONArray().apply {
                        lines.forEach { put(it) }
                    })
                    put("target_lang", deeplLangCode)
                    if (formality != "default") {
                        put("formality", formality)
                    }
                    put("preserve_formatting", true)
                }

                val request = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody(JSON))
                    .build()

                val responseBody = client.newCall(request).awaitTranslationBody()
                    ?: throw TranslationRequestException(null, null, "Translation response is empty")

                val jsonResponse = JSONObject(responseBody)
                val translations = jsonResponse.optJSONArray("translations")
                if (translations != null && translations.length() > 0) {
                    val translatedLines = (0 until translations.length()).map { i ->
                        translations.getJSONObject(i).optString("text", "")
                    }
                    
                    return@withContext Result.success(
                        alignProviderTranslationLines(translatedLines, lineCount, strictLineCount),
                    )
                }
                throw TranslationRequestException(null, null, "Translation response is invalid")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e.asTranslationRequestException()
                if (failure.httpStatusCode != null && failure.httpStatusCode !in 500..599) {
                    return@withContext Result.failure(failure)
                }
                lastFailure = failure
            }
            currentAttempt++
            if (currentAttempt < maxRetries) delay(1000L * currentAttempt)
        }
        return@withContext Result.failure(
            lastFailure ?: TranslationRequestException(null, null, "Max retries exceeded"),
        )
    }
}

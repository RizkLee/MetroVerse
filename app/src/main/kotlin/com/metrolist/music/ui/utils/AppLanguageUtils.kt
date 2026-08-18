/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.metrolist.music.constants.LanguageCodeToName
import java.util.Locale

@Composable
fun rememberEffectiveAppLanguageCode(): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { effectiveAppLanguageCode(locale) }
}

internal fun effectiveAppLanguageCode(locale: Locale): String {
    val language = locale.language.lowercase(Locale.US)
    val country = locale.country.uppercase(Locale.US)
    val script = locale.script

    val preferredCode =
        when (language) {
            "zh" ->
                when {
                    country == "HK" -> "zh-HK"
                    script.equals("Hant", ignoreCase = true) || country in setOf("TW", "MO") -> "zh-TW"
                    else -> "zh-CN"
                }
            "en" -> if (country == "GB") "en-GB" else "en"
            "es" -> if (country.isNotEmpty() && country != "ES") "es-419" else "es"
            "fr" -> if (country == "CA") "fr-CA" else "fr"
            "pt" -> if (country == "PT") "pt-PT" else "pt"
            "he" -> "iw"
            "nb", "nn" -> "no"
            else -> language
        }

    return preferredCode.takeIf(LanguageCodeToName::containsKey)
        ?: language.takeIf(LanguageCodeToName::containsKey)
        ?: "en"
}

private val romanizationLanguages =
    listOf(
        "Japanese",
        "Korean",
        "Chinese",
        "Hindi",
        "Punjabi",
        "Russian",
        "Ukrainian",
        "Serbian",
        "Bulgarian",
        "Belarusian",
        "Kyrgyz",
        "Macedonian",
    )

fun defaultRomanizationLanguageSettings(appLanguageCode: String): List<Pair<String, Boolean>> {
    val appLanguage = romanizationLanguageFor(appLanguageCode)
    return romanizationLanguages.map { language -> language to (language != appLanguage) }
}

internal fun romanizationLanguageFor(appLanguageCode: String): String? =
    when (appLanguageCode.substringBefore('-')) {
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "zh" -> "Chinese"
        "hi" -> "Hindi"
        "pa" -> "Punjabi"
        "ru" -> "Russian"
        "uk" -> "Ukrainian"
        "sr" -> "Serbian"
        "bg" -> "Bulgarian"
        "be" -> "Belarusian"
        "ky" -> "Kyrgyz"
        "mk" -> "Macedonian"
        else -> null
    }

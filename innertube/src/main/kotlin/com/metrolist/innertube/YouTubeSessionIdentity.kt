/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube

fun normalizeYouTubeAuthUser(value: String?): String =
    value?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: "0"

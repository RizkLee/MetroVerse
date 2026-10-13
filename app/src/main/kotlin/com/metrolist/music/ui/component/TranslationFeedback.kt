/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Hide feedback without clearing the failure that suppresses automatic paid retries. */
@Composable
internal fun rememberTranslationErrorVisible(status: Any, isError: Boolean): Boolean {
    var visible by remember(status) { mutableStateOf(true) }
    LaunchedEffect(status) {
        if (isError) {
            delay(6_000)
            visible = false
        }
    }
    return visible
}

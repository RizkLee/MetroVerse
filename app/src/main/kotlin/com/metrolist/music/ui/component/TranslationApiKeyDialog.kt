/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R

@Composable
internal fun TranslationApiKeyDialog(
    onDismiss: () -> Unit,
    onConfigure: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.key), contentDescription = null) },
        title = { Text(stringResource(R.string.ai_api_key_required_title)) },
        text = { Text(stringResource(R.string.ai_api_key_required_message)) },
        confirmButton = {
            TextButton(onClick = onConfigure) {
                Text(stringResource(R.string.ai_configure_api_key))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

@Composable
internal fun AsrModelDownloadDialog(
    modelBytes: Long,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    startRecognitionAfterDownload: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_subtitle_asr_download_model)) },
        icon = { Icon(painterResource(R.drawable.download), contentDescription = null) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.podcast_asr_experimental_desc,
                        Formatter.formatShortFileSize(LocalContext.current, modelBytes),
                    ),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onImport) {
                    Text(stringResource(R.string.podcast_subtitle_asr_import_model))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(
                    stringResource(
                        if (startRecognitionAfterDownload) R.string.podcast_subtitle_asr_download_and_start
                        else R.string.action_download,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

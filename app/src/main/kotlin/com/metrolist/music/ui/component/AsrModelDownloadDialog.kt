/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    // Both palettes keep at least 3:1 contrast at this background-luminance crossover.
    val darkSurface = AlertDialogDefaults.containerColor.luminance() < 0.21f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_subtitle_asr_download_model)) },
        icon = { Icon(painterResource(R.drawable.download), contentDescription = null) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ModelNoticeRow(
                    icon = R.drawable.mic,
                    tint = if (darkSurface) Color(0xFFC7E3FF) else Color(0xFF003366),
                    text = stringResource(
                        R.string.podcast_asr_experimental_desc,
                        Formatter.formatShortFileSize(LocalContext.current, modelBytes),
                    ),
                )
                ModelNoticeRow(
                    icon = R.drawable.language,
                    tint = if (darkSurface) Color(0xFFB2F5E5) else Color(0xFF003C32),
                    text = stringResource(R.string.podcast_asr_english_only),
                )
                ModelNoticeRow(
                    icon = R.drawable.speed,
                    tint = if (darkSurface) Color(0xFFEAD7FF) else Color(0xFF461961),
                    text = stringResource(R.string.podcast_asr_performance_notice),
                )
                ModelNoticeRow(
                    icon = R.drawable.warning,
                    tint = if (darkSurface) Color(0xFFFFE3AF) else Color(0xFF4C2C00),
                    text = stringResource(R.string.podcast_asr_device_warning),
                )
                TextButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.podcast_subtitle_asr_import_model))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(
                        if (startRecognitionAfterDownload) R.string.podcast_subtitle_asr_download_and_start
                        else R.string.action_download,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ModelNoticeRow(
    @DrawableRes icon: Int,
    tint: Color,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

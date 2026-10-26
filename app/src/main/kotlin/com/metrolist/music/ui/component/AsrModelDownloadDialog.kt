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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.AsrLanguage
import com.metrolist.music.podcast.transcript.AsrModel
import com.metrolist.music.podcast.transcript.AsrModelVariant
import com.metrolist.music.podcast.transcript.OnDeviceTranscriber

@Composable
internal fun AsrModelDownloadDialog(
    modelBytes: Long,
    language: com.metrolist.music.podcast.transcript.AsrLanguage = com.metrolist.music.podcast.transcript.AsrLanguage.ENGLISH,
    onDismiss: () -> Unit,
    onDownload: (AsrModelVariant) -> Unit,
    startRecognitionAfterDownload: Boolean = false,
    initialVariant: AsrModelVariant = language.modelVariant,
) {
    var variant by remember(language, initialVariant) { mutableStateOf(initialVariant) }
    val downloadBytes = if (variant == initialVariant) modelBytes else OnDeviceTranscriber.approximateModelDownloadBytes(language, variant)
    // Both palettes keep at least 3:1 contrast at this background-luminance crossover.
    val darkSurface = AlertDialogDefaults.containerColor.luminance() < 0.21f
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 320.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.podcast_subtitle_asr_download_model)) },
        icon = { Icon(painterResource(R.drawable.download), contentDescription = null) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AsrModelVariantSelector(language, variant) { variant = it }
                ModelNoticeRow(
                    icon = R.drawable.language,
                    tint = MaterialTheme.colorScheme.primary,
                    text = stringResource(R.string.podcast_asr_model_information, asrLanguageName(language.code), variant.displayName),
                )
                ModelNoticeRow(
                    icon = R.drawable.file_save,
                    tint = MaterialTheme.colorScheme.primary,
                    text = stringResource(R.string.podcast_asr_model_size, Formatter.formatShortFileSize(LocalContext.current, downloadBytes)),
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(variant) }, modifier = Modifier.heightIn(min = 48.dp)) {
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
internal fun AsrModelManagementDialog(
    language: AsrLanguage,
    initialVariant: AsrModelVariant,
    readyModels: Set<AsrModel>,
    storageSizes: Map<AsrModel, Long>,
    modelsInUse: Set<AsrModel>,
    onDismiss: () -> Unit,
    onUse: (AsrModelVariant) -> Unit,
    onDownload: (AsrModelVariant) -> Unit,
    onDelete: ((AsrModelVariant) -> Unit)? = null,
    busy: Boolean = false,
    downloadProgress: Pair<Long, Long>? = null,
    error: String? = null,
) {
    var variant by remember(language, initialVariant) { mutableStateOf(initialVariant) }
    val model = language.model(variant)
    val ready = model in readyModels
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 360.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.podcast_asr_language_model, asrLanguageName(language.code))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AsrModelVariantSelector(language, variant, enabled = !busy) { variant = it }
                Text(variant.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (ready) stringResource(R.string.podcast_asr_model_ready_size, Formatter.formatShortFileSize(context, storageSizes[model] ?: 0L))
                    else stringResource(R.string.podcast_asr_model_not_downloaded, Formatter.formatShortFileSize(context, OnDeviceTranscriber.approximateModelDownloadBytes(language, variant))),
                )
                Text(stringResource(R.string.podcast_asr_performance_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.podcast_asr_model_next_task), style = MaterialTheme.typography.bodySmall)
                if (busy) {
                    val progress = downloadProgress
                    if (progress != null && progress.second > 0L) {
                        val fraction = (progress.first.toFloat() / progress.second).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.podcast_subtitle_asr_model_downloading, (fraction * 100).toInt()))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.podcast_subtitle_asr_model_busy))
                    }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                if (ready && onDelete != null) {
                    OutlinedButton(onClick = { onDelete(variant) }, enabled = !busy && model !in modelsInUse) {
                        Text(stringResource(R.string.podcast_subtitle_delete_model))
                    }
                    if (model in modelsInUse) Text(stringResource(R.string.podcast_asr_model_in_use), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ready) onUse(variant) else onDownload(variant) }, enabled = !busy) {
                Text(stringResource(if (ready) R.string.podcast_asr_use_model else R.string.action_download))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun AsrModelVariantSelector(language: AsrLanguage, selected: AsrModelVariant, enabled: Boolean = true, onSelect: (AsrModelVariant) -> Unit) {
    if (language.supportedModelVariants.size < 2) return
    Column {
        language.supportedModelVariants.forEach { variant ->
            Row(
                Modifier.fillMaxWidth().selectable(selected == variant, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(variant) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == variant, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(if (variant == AsrModelVariant.TINY_STREAMING) "Tiny" else "Small", style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (variant == AsrModelVariant.TINY_STREAMING) R.string.podcast_asr_tiny_description else R.string.podcast_asr_small_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
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

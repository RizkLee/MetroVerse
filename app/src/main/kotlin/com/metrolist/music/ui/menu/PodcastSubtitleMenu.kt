/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.menu

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPodcastSubtitleManager
import com.metrolist.music.R
import com.metrolist.music.constants.PodcastSubtitleTranslationEnabledKey
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.AsrModelDownloadDialog
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.TranslationApiKeyDialog
import com.metrolist.music.utils.rememberPreference

@Composable
fun PodcastSubtitleMenu(
    onDismiss: () -> Unit,
    onResync: () -> Unit,
    onConfigureAi: () -> Unit,
) {
    val manager = LocalPodcastSubtitleManager.current
    var translationEnabled by rememberPreference(PodcastSubtitleTranslationEnabledKey, false)
    val provider by rememberPreference(AiProviderKey, "OpenRouter")
    val apiKey by rememberPreference(OpenRouterApiKey, "")
    val deeplApiKey by rememberPreference(DeeplApiKey, "")
    val hasApiKey = if (provider == "DeepL") deeplApiKey.isNotBlank() else apiKey.isNotBlank()
    var showApiKeyPrompt by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    val modelReady = manager?.asrModelReady?.collectAsStateWithLifecycle()?.value == true
    val modelBusy = manager?.modelOperationBusy?.collectAsStateWithLifecycle()?.value == true
    val modelError = manager?.modelDownloadError?.collectAsStateWithLifecycle()?.value
    val downloadProgress = manager?.modelDownloadProgress?.collectAsStateWithLifecycle()?.value
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) manager?.importAsrModel(uri)
    }

    fun setTranslationEnabled(enabled: Boolean) {
        if (enabled && !hasApiKey) {
            showApiKeyPrompt = true
        } else {
            translationEnabled = enabled
            if (!enabled) manager?.stopTranslationRequests()
        }
    }

    if (showApiKeyPrompt) {
        TranslationApiKeyDialog(
            onDismiss = { showApiKeyPrompt = false },
            onConfigure = {
                showApiKeyPrompt = false
                onDismiss()
                onConfigureAi()
            },
        )
    }

    if (showDownloadDialog) {
        AsrModelDownloadDialog(
            modelBytes = manager?.asrModelDownloadBytes() ?: 0L,
            onDismiss = { showDownloadDialog = false },
            onDownload = {
                showDownloadDialog = false
                manager?.downloadAsrModel()
            },
            onImport = {
                showDownloadDialog = false
                importModel.launch(null)
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.podcast_subtitle_delete_model)) },
            text = { Text(stringResource(R.string.podcast_subtitle_delete_model_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager?.deleteAsrModel()
                        showDeleteDialog = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Material3MenuGroup(
        items = listOf(
            Material3MenuItemData(
                icon = { Icon(painterResource(R.drawable.translate), null) },
                title = { Text(stringResource(R.string.podcast_subtitles_translation)) },
                description = { Text(stringResource(R.string.podcast_subtitles_translation_desc)) },
                onClick = { setTranslationEnabled(!translationEnabled) },
                trailingContent = {
                    Switch(
                        checked = translationEnabled,
                        onCheckedChange = ::setTranslationEnabled,
                        thumbContent = {
                            Icon(
                                painterResource(if (translationEnabled) R.drawable.check else R.drawable.close),
                                null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        },
                    )
                },
            ),
            Material3MenuItemData(
                icon = { Icon(painterResource(R.drawable.sync), null) },
                title = { Text(stringResource(R.string.resync)) },
                onClick = {
                    onResync()
                    onDismiss()
                },
            ),
            Material3MenuItemData(
                icon = { Icon(painterResource(if (modelReady) R.drawable.delete else R.drawable.download), null) },
                title = {
                    Text(stringResource(when {
                        modelBusy -> R.string.podcast_subtitle_asr_model
                        modelReady -> R.string.podcast_subtitle_delete_model
                        modelError != null -> R.string.podcast_subtitle_asr_download_retry
                        else -> R.string.podcast_subtitle_asr_download_model
                    }))
                },
                description = {
                    val progress = downloadProgress
                    Text(when {
                        progress != null -> stringResource(
                            R.string.podcast_subtitle_asr_model_downloading,
                            if (progress.second > 0L) (progress.first * 100L / progress.second).toInt().coerceIn(0, 100) else 0,
                        )
                        modelBusy -> stringResource(R.string.podcast_subtitle_asr_model_busy)
                        modelError != null -> modelError.orEmpty()
                        modelReady -> stringResource(R.string.podcast_subtitle_model_ready)
                        else -> stringResource(
                            R.string.podcast_subtitle_asr_model_details,
                            Formatter.formatShortFileSize(LocalContext.current, manager?.asrModelDownloadBytes() ?: 0L),
                        )
                    })
                },
                trailingContent = if (modelBusy) ({
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }) else null,
                onClick = if (manager != null && !modelBusy) ({
                    if (modelReady) showDeleteDialog = true else showDownloadDialog = true
                }) else null,
            ),
        ),
    )
}

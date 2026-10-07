/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPodcastSubtitleManager
import com.metrolist.music.R
import com.metrolist.music.constants.AlwaysUseAiPodcastSubtitleTranslationKey
import com.metrolist.music.constants.PodcastSubtitleTranslationEnabledKey
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.rememberPreference

@Composable
internal fun PodcastSubtitleSettings() {
    val manager = LocalPodcastSubtitleManager.current
    var alwaysTranslate by rememberPreference(AlwaysUseAiPodcastSubtitleTranslationKey, false)
    var translationEnabled by rememberPreference(PodcastSubtitleTranslationEnabledKey, false)
    val downloadProgress = manager?.modelDownloadProgress?.collectAsStateWithLifecycle()?.value
    var modelDeleted by remember(manager, downloadProgress) { mutableStateOf(false) }
    val modelReady = !modelDeleted && manager?.isAsrModelReady() == true
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.podcast_subtitle_delete_model)) },
            text = { Text(stringResource(R.string.podcast_subtitle_delete_model_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager?.deleteAsrModel()
                        modelDeleted = true
                        showDeleteDialog = false
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

    Material3SettingsGroup(
        title = stringResource(R.string.settings_title_podcast_subtitles),
        items = listOf(
            Material3SettingsItem(
                icon = painterResource(R.drawable.subtitles),
                title = { Text(stringResource(R.string.ai_always_translate_podcast_subtitles)) },
                description = { Text(stringResource(R.string.ai_always_translate_podcast_subtitles_desc)) },
                onClick = {
                    val enabled = !alwaysTranslate
                    alwaysTranslate = enabled
                    translationEnabled = enabled
                },
                trailingContent = {
                    Switch(
                        checked = alwaysTranslate,
                        onCheckedChange = {
                            alwaysTranslate = it
                            translationEnabled = it
                        },
                        thumbContent = {
                            Icon(
                                painterResource(if (alwaysTranslate) R.drawable.check else R.drawable.close),
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        },
                    )
                },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.delete),
                title = { Text(stringResource(R.string.podcast_subtitle_delete_model)) },
                description = {
                    Text(
                        stringResource(
                            if (modelReady) R.string.podcast_subtitle_model_ready
                            else R.string.podcast_subtitle_asr_download_model,
                        ),
                    )
                },
                enabled = modelReady,
                onClick = { showDeleteDialog = true },
            ),
        ),
    )
}

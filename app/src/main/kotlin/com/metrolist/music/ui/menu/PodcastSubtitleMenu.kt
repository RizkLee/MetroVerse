/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.menu

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
import com.metrolist.music.LocalPodcastSubtitleManager
import com.metrolist.music.R
import com.metrolist.music.constants.PodcastSubtitleTranslationEnabledKey
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.utils.rememberPreference

@Composable
fun PodcastSubtitleMenu(
    onDismiss: () -> Unit,
    onResync: () -> Unit,
) {
    val manager = LocalPodcastSubtitleManager.current
    var translationEnabled by rememberPreference(PodcastSubtitleTranslationEnabledKey, false)
    var showDeleteDialog by remember { mutableStateOf(false) }
    val modelReady = manager?.isAsrModelReady() == true

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
                onClick = { translationEnabled = !translationEnabled },
                trailingContent = {
                    Switch(
                        checked = translationEnabled,
                        onCheckedChange = { translationEnabled = it },
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
                icon = { Icon(painterResource(R.drawable.subtitles), null) },
                title = { Text(stringResource(R.string.podcast_asr_experimental)) },
                description = {
                    Text(
                        stringResource(
                            if (modelReady) {
                                R.string.podcast_subtitle_model_ready
                            } else {
                                R.string.podcast_subtitle_asr_download_model
                            },
                        ),
                    )
                },
                onClick = if (modelReady) ({ showDeleteDialog = true }) else null,
            ),
        ),
    )
}

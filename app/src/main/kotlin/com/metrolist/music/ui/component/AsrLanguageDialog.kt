/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.AsrLanguage
import com.metrolist.music.podcast.transcript.AsrLanguageSelection

@Composable
internal fun AsrLanguageDialog(
    selection: AsrLanguageSelection,
    onDismiss: () -> Unit,
    onSave: (AsrLanguage, Boolean) -> Unit,
) {
    var language by remember(selection) { mutableStateOf(selection.language) }
    var useForChannel by remember(selection) { mutableStateOf(selection.channelDefault != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 320.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.podcast_asr_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!selection.isSupported) Text(
                    stringResource(R.string.podcast_asr_language_unsupported, asrLanguageName(selection.code)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsrLanguage.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                                selected = language == option, role = Role.RadioButton, onClick = { language = option },
                            ).padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == option, onClick = null)
                            Text(asrLanguageName(option.code), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (selection.channelId != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                            value = useForChannel, role = Role.Checkbox, onValueChange = { useForChannel = it },
                        ).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = useForChannel, onCheckedChange = null)
                        Text(stringResource(R.string.podcast_asr_channel_default, selection.channelName), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = language != null,
                onClick = { language?.let { onSave(it, useForChannel) }; onDismiss() }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

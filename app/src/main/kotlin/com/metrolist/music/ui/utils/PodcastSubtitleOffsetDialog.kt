/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.SubtitleTimingOffset
import kotlin.math.roundToInt

@Composable
internal fun PodcastSubtitleOffsetDialog(
    episodeId: String,
    initialOffsetMs: Int,
    onOffsetChange: (Int) -> Unit,
) {
    var offsetMs by rememberSaveable(episodeId) { mutableIntStateOf(initialOffsetMs) }
    var textValue by rememberSaveable(episodeId) { mutableStateOf(initialOffsetMs.toString()) }
    val focusManager = LocalFocusManager.current

    fun updateOffset(value: Int) {
        offsetMs = SubtitleTimingOffset.clamp(value)
        textValue = offsetMs.toString()
        onOffsetChange(offsetMs)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.podcast_subtitle_offset),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.podcast_subtitle_offset_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = textValue,
            onValueChange = { value ->
                if (value.length <= 6 && value.withIndex().all { (index, character) ->
                        character in '0'..'9' || (index == 0 && character == '-')
                    }
                ) {
                    textValue = value
                    value.toIntOrNull()?.let { parsed ->
                        offsetMs = SubtitleTimingOffset.clamp(parsed)
                        if (parsed != offsetMs) textValue = offsetMs.toString()
                        onOffsetChange(offsetMs)
                    }
                }
            },
            label = { Text(stringResource(R.string.podcast_subtitle_offset_label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                textValue = offsetMs.toString()
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) textValue = offsetMs.toString() },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { updateOffset(offsetMs - SubtitleTimingOffset.STEP_MS) },
                enabled = offsetMs > -SubtitleTimingOffset.LIMIT_MS,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.remove),
                    contentDescription = stringResource(R.string.podcast_subtitle_offset_later),
                )
            }
            Slider(
                value = offsetMs.toFloat(),
                onValueChange = { value ->
                    offsetMs = SubtitleTimingOffset.clamp(
                        (value / SubtitleTimingOffset.STEP_MS).roundToInt() * SubtitleTimingOffset.STEP_MS,
                    )
                    textValue = offsetMs.toString()
                },
                onValueChangeFinished = { onOffsetChange(offsetMs) },
                valueRange = -SubtitleTimingOffset.LIMIT_MS.toFloat()..SubtitleTimingOffset.LIMIT_MS.toFloat(),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { updateOffset(offsetMs + SubtitleTimingOffset.STEP_MS) },
                enabled = offsetMs < SubtitleTimingOffset.LIMIT_MS,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = stringResource(R.string.podcast_subtitle_offset_earlier),
                )
            }
        }
        TextButton(
            onClick = { updateOffset(0) },
            enabled = offsetMs != 0,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.reset))
        }
    }
}

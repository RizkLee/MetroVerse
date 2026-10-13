/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.ui.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Icon(painterResource(R.drawable.fast_forward), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.podcast_subtitle_offset), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = textValue,
                onValueChange = { value ->
                    if (value.length <= 6 && value.withIndex().all { (index, character) ->
                            character in '0'..'9' || (index == 0 && character == '-')
                        }) {
                        textValue = value
                        value.toIntOrNull()?.let { parsed ->
                            offsetMs = SubtitleTimingOffset.clamp(parsed)
                            if (parsed != offsetMs) textValue = offsetMs.toString()
                            onOffsetChange(offsetMs)
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { textValue = offsetMs.toString(); focusManager.clearFocus() }),
                modifier = Modifier.widthIn(min = 120.dp, max = 160.dp).onFocusChanged { if (!it.isFocused) textValue = offsetMs.toString() },
            )
            Spacer(Modifier.width(8.dp))
            Text("ms", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            if (offsetMs != 0) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { updateOffset(0) }) {
                    Icon(painterResource(R.drawable.replay), stringResource(R.string.reset), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { updateOffset(offsetMs - SubtitleTimingOffset.STEP_MS) }, enabled = offsetMs > -SubtitleTimingOffset.LIMIT_MS) {
                Icon(painterResource(R.drawable.remove), stringResource(R.string.podcast_subtitle_offset_later))
            }
            Slider(
                value = offsetMs.toFloat(),
                onValueChange = {
                    offsetMs = SubtitleTimingOffset.clamp((it / SubtitleTimingOffset.STEP_MS).roundToInt() * SubtitleTimingOffset.STEP_MS)
                    textValue = offsetMs.toString()
                },
                onValueChangeFinished = { onOffsetChange(offsetMs) },
                valueRange = -SubtitleTimingOffset.LIMIT_MS.toFloat()..SubtitleTimingOffset.LIMIT_MS.toFloat(),
                steps = 2 * SubtitleTimingOffset.LIMIT_MS / SubtitleTimingOffset.STEP_MS - 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { updateOffset(offsetMs + SubtitleTimingOffset.STEP_MS) }, enabled = offsetMs < SubtitleTimingOffset.LIMIT_MS) {
                Icon(painterResource(R.drawable.add), stringResource(R.string.podcast_subtitle_offset_earlier))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
            Text("-${SubtitleTimingOffset.LIMIT_MS}ms", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("+${SubtitleTimingOffset.LIMIT_MS}ms", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

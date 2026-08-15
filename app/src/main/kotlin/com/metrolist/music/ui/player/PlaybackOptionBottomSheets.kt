package com.metrolist.music.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SleepTimerStopAfterCurrentSongKey
import com.metrolist.music.utils.rememberPreference
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedBottomSheet(
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    var speed by remember {
        mutableFloatStateOf(playerConnection.player.playbackParameters.speed)
    }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.speed),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = String.format(Locale.getDefault(), "%.2fx", speed),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Slider(
                value = speed,
                onValueChange = { value ->
                    speed = value
                    playerConnection.player.playbackParameters = PlaybackParameters(value)
                },
                valueRange = 0.25f..4f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSleepTimerBottomSheet(
    isEpisode: Boolean,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val timer = playerConnection.service.sleepTimer ?: return
    val stopAfterCurrentSong by rememberPreference(SleepTimerStopAfterCurrentSongKey, false)
    val fadeOut by rememberPreference(SleepTimerFadeOutKey, false)
    val initialMinutes = remember(timer.triggerTime) {
        if (timer.triggerTime > 0L) {
            ceil((timer.triggerTime - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000.0)
                .toInt()
                .coerceIn(5, MAX_SLEEP_MINUTES)
        } else {
            0
        }
    }
    var sliderStep by remember { mutableFloatStateOf((initialMinutes / MINUTE_STEP).toFloat()) }
    var endOfMedia by remember { mutableStateOf(timer.pauseWhenSongEnd) }
    val selectedMinutes = sliderStep.roundToInt() * MINUTE_STEP
    val stateText = when {
        endOfMedia -> stringResource(if (isEpisode) R.string.end_of_episode else R.string.end_of_song)
        selectedMinutes > 0 -> pluralStringResource(R.plurals.minute, selectedMinutes, selectedMinutes)
        else -> stringResource(R.string.sleep_timer_off)
    }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.bedtime),
                    contentDescription = null,
                    tint = if (timer.isActive || selectedMinutes > 0 || endOfMedia) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stateText,
                    color = if (timer.isActive || selectedMinutes > 0 || endOfMedia) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            Slider(
                value = sliderStep,
                onValueChange = { value ->
                    sliderStep = value
                    if (value.roundToInt() > 0) endOfMedia = false
                },
                onValueChangeFinished = {
                    val minutes = sliderStep.roundToInt() * MINUTE_STEP
                    if (minutes == 0) {
                        timer.clear()
                    } else {
                        timer.start(
                            minute = minutes,
                            stopAfterCurrentSong = stopAfterCurrentSong,
                            fadeOut = fadeOut,
                        )
                    }
                },
                valueRange = 0f..(MAX_SLEEP_MINUTES / MINUTE_STEP).toFloat(),
                steps = MAX_SLEEP_MINUTES / MINUTE_STEP - 1,
                modifier = Modifier.fillMaxWidth(),
            )

            FilterChip(
                selected = endOfMedia,
                onClick = {
                    if (endOfMedia) {
                        endOfMedia = false
                        sliderStep = 0f
                        timer.clear()
                    } else {
                        endOfMedia = true
                        sliderStep = 0f
                        timer.start(
                            minute = -1,
                            stopAfterCurrentSong = false,
                            fadeOut = fadeOut,
                        )
                    }
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.bedtime),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Text(stringResource(if (isEpisode) R.string.end_of_episode else R.string.end_of_song))
                },
            )
        }
    }
}

private const val MINUTE_STEP = 5
private const val MAX_SLEEP_MINUTES = 90

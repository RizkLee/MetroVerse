/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.R
import com.metrolist.music.constants.AppUsageMode

@Composable
fun UsageModeOnboardingDialog(
    isOnboarding: Boolean,
    initialMode: AppUsageMode,
    onConfirm: (AppUsageMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedMode by rememberSaveable(initialMode) { mutableStateOf(initialMode) }

    Dialog(
        onDismissRequest = { if (!isOnboarding) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = !isOnboarding,
                dismissOnClickOutside = !isOnboarding,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier =
                Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 440.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            if (isOnboarding) R.string.usage_mode_onboarding_title else R.string.app_usage_mode,
                        ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.usage_mode_onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                UsageModeOptionCard(
                    selected = selectedMode == AppUsageMode.MUSIC_AND_PODCASTS,
                    iconRes = R.drawable.music_note,
                    title = stringResource(R.string.usage_mode_music_and_podcasts),
                    description = stringResource(R.string.usage_mode_music_and_podcasts_desc),
                    onClick = { selectedMode = AppUsageMode.MUSIC_AND_PODCASTS },
                )

                Spacer(modifier = Modifier.height(10.dp))

                UsageModeOptionCard(
                    selected = selectedMode == AppUsageMode.PODCASTS_ONLY,
                    iconRes = R.drawable.podcast,
                    title = stringResource(R.string.usage_mode_podcasts_only),
                    description = stringResource(R.string.usage_mode_podcasts_only_desc),
                    onClick = { selectedMode = AppUsageMode.PODCASTS_ONLY },
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.usage_mode_youtube_music_region_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (selectedMode == AppUsageMode.PODCASTS_ONLY) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.info),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.usage_mode_restore_in_settings_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!isOnboarding) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(onClick = { onConfirm(selectedMode) }) {
                        Text(
                            stringResource(
                                if (isOnboarding) R.string.usage_mode_continue else android.R.string.ok,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageModeOptionCard(
    selected: Boolean,
    iconRes: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        border =
            BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
        }
    }
}

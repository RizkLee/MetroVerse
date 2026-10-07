package com.metrolist.music.ui.screens.settings

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.cache.TEXT_CACHE_LIMITS_MIB
import com.metrolist.music.cache.TextCacheClearTarget
import com.metrolist.music.ui.component.ActionPromptDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.viewmodels.TextCacheViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun TextCacheSettings(viewModel: TextCacheViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var clearTarget by remember { mutableStateOf<TextCacheClearTarget?>(null) }
    var selectedLimit by remember(state.limitMiB) { mutableStateOf(state.limitMiB) }
    var includeUnverified by remember(clearTarget) { mutableStateOf(false) }

    fun showResult(success: Boolean) {
        Toast.makeText(context, if (success) R.string.text_cache_clear_done else R.string.text_cache_clear_failed, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(viewModel) {
        try {
            viewModel.refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            showResult(false)
        }
    }

    clearTarget?.let { target ->
        ActionPromptDialog(
            title = stringResource(when (target) {
                TextCacheClearTarget.ALL -> R.string.text_cache_clear_all
                TextCacheClearTarget.PODCASTS -> R.string.text_cache_clear_subtitles
                TextCacheClearTarget.LYRICS -> R.string.text_cache_clear_lyrics
            }),
            onDismiss = { clearTarget = null },
            onCancel = { clearTarget = null },
            onConfirm = {
                val includeOldLyrics = includeUnverified && target != TextCacheClearTarget.PODCASTS
                clearTarget = null
                clearing = true
                scope.launch {
                    try {
                        showResult(viewModel.clear(target, includeOldLyrics))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        showResult(false)
                    } finally {
                        clearing = false
                    }
                }
            },
            content = {
                Column {
                    Text(stringResource(if (target == TextCacheClearTarget.PODCASTS) R.string.text_cache_clear_podcasts_confirm else R.string.text_cache_clear_confirm))
                    if (target != TextCacheClearTarget.PODCASTS) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = includeUnverified,
                                role = Role.Checkbox,
                                onValueChange = { includeUnverified = it },
                            ).padding(vertical = 8.dp),
                        ) {
                            Checkbox(checked = includeUnverified, onCheckedChange = null)
                            Text(stringResource(R.string.text_cache_include_unverified), modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
        )
    }

    Material3SettingsGroup(
        title = stringResource(R.string.text_cache_title),
        items = buildList {
            add(Material3SettingsItem(
                icon = painterResource(R.drawable.storage),
                title = { Text(stringResource(R.string.text_cache_limit)) },
                description = {
                    Column {
                        if (state.loading) {
                            Text(stringResource(R.string.text_cache_loading))
                        } else {
                            Text("${Formatter.formatShortFileSize(context, state.totalBytes)} / ${Formatter.formatShortFileSize(context, selectedLimit * 1024L * 1024L)}")
                        }
                        Slider(
                            value = TEXT_CACHE_LIMITS_MIB.indexOf(selectedLimit).coerceAtLeast(0).toFloat(),
                            onValueChange = { selectedLimit = TEXT_CACHE_LIMITS_MIB[it.roundToInt()] },
                            onValueChangeFinished = {
                                scope.launch {
                                    try {
                                        if (!viewModel.setLimit(selectedLimit)) showResult(false)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        showResult(false)
                                    }
                                }
                            },
                            valueRange = 0f..TEXT_CACHE_LIMITS_MIB.lastIndex.toFloat(),
                            steps = TEXT_CACHE_LIMITS_MIB.size - 2,
                            enabled = !clearing && !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            ))
            add(Material3SettingsItem(
                title = { Text(stringResource(R.string.text_cache_subtitles)) },
                description = { Text(Formatter.formatShortFileSize(context, state.podcastBytes)) },
            ))
            add(Material3SettingsItem(
                title = { Text(stringResource(R.string.text_cache_lyrics)) },
                description = { Text(Formatter.formatShortFileSize(context, state.lyricsBytes)) },
            ))
            if (state.protectedBytes > 0) add(Material3SettingsItem(
                title = { Text(stringResource(R.string.text_cache_protected)) },
                description = { Text(Formatter.formatShortFileSize(context, state.protectedBytes)) },
            ))
            for ((target, label) in listOf(
                TextCacheClearTarget.PODCASTS to R.string.text_cache_clear_subtitles,
                TextCacheClearTarget.LYRICS to R.string.text_cache_clear_lyrics,
                TextCacheClearTarget.ALL to R.string.text_cache_clear_all,
            )) {
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(label)) },
                    enabled = !clearing && !state.loading,
                    onClick = { clearTarget = target },
                ))
            }
        },
    )
    for (text in listOf(R.string.text_cache_policy, R.string.text_cache_preserved, R.string.text_cache_storage_note)) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

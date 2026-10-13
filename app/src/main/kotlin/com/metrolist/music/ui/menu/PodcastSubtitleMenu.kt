/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.metrolist.music.constants.AlwaysUseAiPodcastSubtitleTranslationKey
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.podcast.transcript.PodcastSubtitleState
import com.metrolist.music.ui.component.AsrLanguageDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.TranslationApiKeyDialog
import com.metrolist.music.ui.component.asrLanguageName
import com.metrolist.music.utils.rememberPreference

@Composable
fun PodcastSubtitleMenu(
    onDeviceAsrSupported: Boolean,
    onDismiss: () -> Unit,
    onConfigureAi: () -> Unit,
    offsetProvider: () -> Int,
    onShowOffsetDialog: () -> Unit,
    onRestartRecognition: () -> Unit,
) {
    val manager = LocalPodcastSubtitleManager.current
    val context = LocalContext.current
    val clipboardLabel = stringResource(R.string.podcast_subtitles)
    val alwaysTranslate by rememberPreference(AlwaysUseAiPodcastSubtitleTranslationKey, false)
    val episodeOverride = manager?.translationOverride?.collectAsStateWithLifecycle()?.value
    val translationEnabled = episodeOverride ?: alwaysTranslate
    val provider by rememberPreference(AiProviderKey, "OpenRouter")
    val apiKey by rememberPreference(OpenRouterApiKey, "")
    val deeplApiKey by rememberPreference(DeeplApiKey, "")
    val hasApiKey = if (provider == "DeepL") deeplApiKey.isNotBlank() else apiKey.isNotBlank()
    var showApiKeyPrompt by remember { mutableStateOf(false) }
    val modelBusy = manager?.modelOperationBusy?.collectAsStateWithLifecycle()?.value == true
    val language = manager?.asrLanguage?.collectAsStateWithLifecycle()?.value
    val subtitleState = manager?.state?.collectAsStateWithLifecycle()?.value
    var showLanguageDialog by remember { mutableStateOf(false) }

    fun setTranslationEnabled(enabled: Boolean) {
        if (enabled && !hasApiKey) showApiKeyPrompt = true
        else manager?.setEpisodeTranslationEnabled(enabled)
    }

    if (showLanguageDialog && language != null) {
        AsrLanguageDialog(language, { showLanguageDialog = false }, manager::selectAsrLanguage)
    }
    if (showApiKeyPrompt) {
        TranslationApiKeyDialog(
            onDismiss = { showApiKeyPrompt = false },
            onConfigure = { showApiKeyPrompt = false; onDismiss(); onConfigureAi() },
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            NewActionGrid(
                actions = listOf(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.cached),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.podcast_subtitle_rerecognize),
                        enabled = onDeviceAsrSupported && language?.isSupported == true && !modelBusy,
                        onClick = { onDismiss(); onRestartRecognition() },
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.content_copy),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.copy),
                        enabled = manager?.currentCues()?.isNotEmpty() == true,
                        onClick = {
                            val text = manager?.currentCues().orEmpty().joinToString("\n") { it.text }
                            if (text.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, text))
                                Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                ),
                columns = 2,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
            )
        }
        item {
            Material3MenuGroup(
                items = listOfNotNull(
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.translate), null) },
                        title = { Text(stringResource(R.string.podcast_subtitles_translation)) },
                        onClick = { setTranslationEnabled(!translationEnabled) },
                        trailingContent = {
                            Switch(
                                checked = translationEnabled,
                                onCheckedChange = ::setTranslationEnabled,
                                thumbContent = {
                                    Icon(painterResource(if (translationEnabled) R.drawable.check else R.drawable.close), null,
                                        Modifier.size(SwitchDefaults.IconSize))
                                },
                            )
                        },
                    ),
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                        title = { Text(stringResource(R.string.podcast_subtitle_offset)) },
                        trailingContent = {
                            Text(stringResource(R.string.podcast_subtitle_offset_value, offsetProvider()),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        onClick = { onDismiss(); onShowOffsetDialog() },
                    ),
                    if (onDeviceAsrSupported) Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.language), null) },
                        title = { Text(stringResource(R.string.podcast_asr_language)) },
                        description = { Text(asrLanguageName(language?.code)) },
                        onClick = if (!modelBusy && language?.code != null && subtitleState !is PodcastSubtitleState.Transcribing)
                            ({ showLanguageDialog = true }) else null,
                    ) else null,
                ),
            )
        }
    }
}

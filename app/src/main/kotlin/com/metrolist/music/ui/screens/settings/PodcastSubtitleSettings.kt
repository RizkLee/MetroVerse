/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPodcastSubtitleManager
import com.metrolist.music.R
import com.metrolist.music.constants.AlwaysUseAiPodcastSubtitleTranslationKey
import com.metrolist.music.constants.PodcastTranslateLanguageKey
import com.metrolist.music.constants.LanguageCodeToName
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.asrLanguageName
import com.metrolist.music.ui.utils.rememberEffectiveAppLanguageCode
import com.metrolist.music.constants.PodcastAiSystemPromptKey
import com.metrolist.music.constants.DEFAULT_PODCAST_AI_SYSTEM_PROMPT
import com.metrolist.music.podcast.transcript.AsrLanguage
import com.metrolist.music.podcast.transcript.AsrModel
import com.metrolist.music.podcast.transcript.AsrModelVariant
import com.metrolist.music.ui.component.AsrModelManagementDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.utils.rememberPreference

@Composable
internal fun PodcastSubtitleSettings() {
    val manager = LocalPodcastSubtitleManager.current
    val context = LocalContext.current
    var alwaysTranslate by rememberPreference(AlwaysUseAiPodcastSubtitleTranslationKey, false)
    val appLanguage = rememberEffectiveAppLanguageCode()
    var targetLanguage by rememberPreference(PodcastTranslateLanguageKey, appLanguage)
    var showTargetLanguage by remember { mutableStateOf(false) }
    val sizes = manager?.modelSizes?.collectAsStateWithLifecycle()?.value.orEmpty()
    var systemPrompt by rememberPreference(PodcastAiSystemPromptKey, "")
    var showPrompt by remember { mutableStateOf(false) }
    val ready = manager?.readyLanguages?.collectAsStateWithLifecycle()?.value.orEmpty()
    val busy = manager?.modelOperationBusy?.collectAsStateWithLifecycle()?.value == true
    val operationLanguage = manager?.modelOperationLanguage?.collectAsStateWithLifecycle()?.value
    val progress = manager?.modelDownloadProgress?.collectAsStateWithLifecycle()?.value
    val error = manager?.modelDownloadError?.collectAsStateWithLifecycle()?.value
    val variants = manager?.modelVariants?.collectAsStateWithLifecycle()?.value.orEmpty()
    val readyModels = manager?.readyModels?.collectAsStateWithLifecycle()?.value.orEmpty()
    val allSizes = manager?.allModelSizes?.collectAsStateWithLifecycle()?.value.orEmpty()
    val modelsInUse = manager?.modelsInUse?.collectAsStateWithLifecycle()?.value.orEmpty()
    var modelLanguage by remember { mutableStateOf<AsrLanguage?>(null) }
    var deleteModel by remember { mutableStateOf<AsrModel?>(null) }
    if (showTargetLanguage) EnumDialog(
        title = stringResource(R.string.ai_target_language), current = targetLanguage,
        values = LanguageCodeToName.keys.sortedBy { LanguageCodeToName[it] },
        valueText = { LanguageCodeToName[it] ?: it },
        onSelect = { targetLanguage = it; showTargetLanguage = false },
        onDismiss = { showTargetLanguage = false },
    )

    if (showPrompt) TextFieldDialog(
        title = { Text(stringResource(R.string.ai_system_prompt)) },
        initialTextFieldValue = TextFieldValue(systemPrompt.ifBlank { DEFAULT_PODCAST_AI_SYSTEM_PROMPT }),
        singleLine = false, maxLines = 12, isInputValid = { true },
        onDone = { systemPrompt = it.takeUnless { text -> text.isBlank() || text == DEFAULT_PODCAST_AI_SYSTEM_PROMPT }.orEmpty() },
        onDismiss = { showPrompt = false },
        extraContent = {
            TextButton(onClick = { systemPrompt = ""; showPrompt = false }) { Text(stringResource(R.string.ai_system_prompt_reset)) }
        },
    )

    modelLanguage?.let { language ->
        AsrModelManagementDialog(
            language = language,
            initialVariant = variants[language] ?: AsrModelVariant.TINY_STREAMING,
            readyModels = readyModels,
            storageSizes = allSizes,
            modelsInUse = modelsInUse,
            busy = busy,
            downloadProgress = progress,
            error = error,
            onDismiss = { modelLanguage = null },
            onUse = { variant ->
                modelLanguage = null
                manager?.selectAsrModel(language.model(variant))
            },
            onDownload = { variant ->
                manager?.let { controller ->
                    controller.selectAsrModel(language.model(variant)) { controller.downloadAsrModel(language, variant) }
                }
            },
            onDelete = { variant -> modelLanguage = null; deleteModel = language.model(variant) },
        )
    }
    deleteModel?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteModel = null },
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 320.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(stringResource(R.string.podcast_subtitle_delete_model) + " · " + asrLanguageName(model.language.code) + " · " + model.variant.displayName) },
            text = { Text(stringResource(R.string.podcast_subtitle_delete_model_confirm)) },
            confirmButton = { TextButton(onClick = { manager?.deleteAsrModel(model.language, model.variant); deleteModel = null }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { deleteModel = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Material3SettingsGroup(
        title = stringResource(R.string.settings_title_podcast_subtitles),
        items = listOf(
            Material3SettingsItem(
                icon = painterResource(R.drawable.subtitles),
                title = { Text(stringResource(R.string.ai_always_translate_podcast_subtitles)) },
                description = { Text(stringResource(R.string.ai_always_translate_podcast_subtitles_desc)) },
                onClick = { alwaysTranslate = !alwaysTranslate },
                trailingContent = {
                    Switch(checked = alwaysTranslate, onCheckedChange = { alwaysTranslate = it })
                },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.edit),
                title = { Text(stringResource(R.string.ai_system_prompt)) },
                description = { Text(if (systemPrompt.isBlank()) stringResource(R.string.ai_system_prompt_default) else stringResource(R.string.ai_provider_custom)) },
                onClick = { showPrompt = true },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.language),
                title = { Text(stringResource(R.string.ai_target_language)) },
                description = { Text(LanguageCodeToName[targetLanguage] ?: targetLanguage) },
                onClick = { showTargetLanguage = true },
            ),
        ) + AsrLanguage.entries.map { language ->
            val modelReady = language in ready
            Material3SettingsItem(
                icon = painterResource(R.drawable.language),
                title = { Text(stringResource(R.string.podcast_asr_language_model, asrLanguageName(language.code))) },
                description = {
                    val variantName = if ((variants[language] ?: AsrModelVariant.TINY_STREAMING) == AsrModelVariant.TINY_STREAMING) "Tiny" else "Small"
                    Text(variantName + " · " + when {
                        busy && operationLanguage == language && progress != null -> stringResource(
                            R.string.podcast_subtitle_asr_model_downloading,
                            if (progress.second > 0) (progress.first * 100 / progress.second).toInt().coerceIn(0, 100) else 0,
                        )
                        busy && operationLanguage == language -> stringResource(R.string.podcast_subtitle_asr_model_busy)
                        modelReady -> stringResource(R.string.podcast_asr_model_ready_size, Formatter.formatShortFileSize(context, sizes[language] ?: 0L))
                        else -> stringResource(R.string.podcast_asr_model_not_downloaded, Formatter.formatShortFileSize(context, manager?.asrModelDownloadBytes(language) ?: 0L))
                    })
                },
                enabled = manager != null && !busy,
                onClick = { modelLanguage = language },
            )
        },
    )
    if (error != null) Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.AsrExitDiagnostics
import com.metrolist.music.podcast.transcript.AsrExitKind
import com.metrolist.music.podcast.transcript.AsrExitNotice
import com.metrolist.music.podcast.transcript.AsrLanguage
import com.metrolist.music.podcast.transcript.AsrModelVariant
import com.metrolist.music.podcast.transcript.PodcastSubtitleManager

@Composable
internal fun AsrExitNoticeHost(diagnostics: AsrExitDiagnostics, manager: PodcastSubtitleManager) {
    val notice by diagnostics.notice.collectAsStateWithLifecycle()
    var modelLanguage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(diagnostics) { diagnostics.inspectPreviousExit() }

    notice?.let { previous ->
        AsrExitNoticeDialog(
            previous,
            onDismiss = { diagnostics.dismiss(previous.token) },
            onChooseTiny = {
                manager.clearModelDownloadError()
                modelLanguage = previous.model.language.code
                diagnostics.dismiss(previous.token)
            },
        )
    }
    AsrLanguage.fromCode(modelLanguage)?.let { language ->
        val ready by manager.readyModels.collectAsStateWithLifecycle()
        val sizes by manager.allModelSizes.collectAsStateWithLifecycle()
        val modelsInUse by manager.modelsInUse.collectAsStateWithLifecycle()
        val busy by manager.modelOperationBusy.collectAsStateWithLifecycle()
        val progress by manager.modelDownloadProgress.collectAsStateWithLifecycle()
        val error by manager.modelDownloadError.collectAsStateWithLifecycle()
        AsrModelManagementDialog(
            language, AsrModelVariant.TINY_STREAMING, ready, sizes, modelsInUse,
            busy = busy, downloadProgress = progress, error = error,
            onDismiss = { modelLanguage = null },
            onUse = { variant -> manager.selectAsrModel(language.model(variant)) { modelLanguage = null } },
            onDownload = { variant ->
                manager.selectAsrModel(language.model(variant)) { manager.downloadAsrModel(language, variant) }
            },
        )
    }
}

@Composable
internal fun AsrExitNoticeDialog(notice: AsrExitNotice, onDismiss: () -> Unit, onChooseTiny: () -> Unit) {
    val message = when (notice.kind) {
        AsrExitKind.MEMORY -> if (notice.canRecommendTiny) R.string.podcast_asr_exit_memory else R.string.podcast_asr_exit_memory_tiny
        AsrExitKind.NATIVE_CRASH -> R.string.podcast_asr_exit_native
        AsrExitKind.CRASH -> R.string.podcast_asr_exit_crash
        AsrExitKind.NOT_RESPONDING -> R.string.podcast_asr_exit_not_responding
        AsrExitKind.UNKNOWN -> R.string.podcast_asr_exit_unknown
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 360.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.podcast_asr_exit_title, asrLanguageName(notice.model.language.code))) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = if (notice.canRecommendTiny) onChooseTiny else onDismiss) {
                Text(stringResource(if (notice.canRecommendTiny) R.string.podcast_asr_choose_tiny else R.string.close))
            }
        },
        dismissButton = {
            if (notice.canRecommendTiny) TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

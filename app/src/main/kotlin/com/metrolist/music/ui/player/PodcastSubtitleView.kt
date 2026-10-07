/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.PodcastAsrSupport
import com.metrolist.music.podcast.transcript.PodcastSubtitleState
import com.metrolist.music.podcast.transcript.PodcastTranslationStatus
import com.metrolist.music.podcast.transcript.SubtitleCue
import kotlinx.coroutines.delay
import com.metrolist.music.ui.utils.fadingEdge

/**
 * Full-size podcast transcript view shown in the player's thumbnail slot (the same area the
 * inline lyrics use). Displays whole lines without truncation, keeps the active line centered
 * with a lyrics-like highlight, and offers the on-device recognition entry point directly when
 * the episode has no transcript.
 */
@Composable
fun PodcastSubtitleView(
    state: PodcastSubtitleState,
    translations: Map<Int, String>,
    positionProvider: () -> Long,
    showTranslation: Boolean,
    translationMissingCredentials: Boolean,
    textColor: Color,
    translationStatus: PodcastTranslationStatus = PodcastTranslationStatus.Idle,
    modifier: Modifier = Modifier,
    asrDownloadProgress: Pair<Long, Long>? = null,
    asrModelReady: Boolean = false,
    asrModelBusy: Boolean = false,
    asrModelError: String? = null,
    onSeek: (Long) -> Unit = {},
    onStartTranscription: () -> Unit = {},
    onCancelTranscription: () -> Unit = {},
    onDownloadModel: () -> Unit = {},
    onCurrentCueChanged: (Int) -> Unit = {},
    onShareCues: (List<SubtitleCue>) -> Unit = {},
    resyncToken: Int = 0,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            PodcastSubtitleState.Idle -> Unit

            PodcastSubtitleState.Loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = stringResource(R.string.podcast_subtitle_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            PodcastSubtitleState.Unavailable -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = asrModelError ?: stringResource(R.string.podcast_subtitle_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    AsrEntry(
                        asrModelReady = asrModelReady,
                        asrModelBusy = asrModelBusy,
                        asrDownloadProgress = asrDownloadProgress,
                        textColor = textColor,
                        onStartTranscription = onStartTranscription,
                        onDownloadModel = onDownloadModel,
                    )
                }
            }

            is PodcastSubtitleState.TranscriptionFailed -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = asrModelError ?: state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    AsrEntry(
                        asrModelReady = asrModelReady,
                        asrModelBusy = asrModelBusy,
                        asrDownloadProgress = asrDownloadProgress,
                        textColor = textColor,
                        label = stringResource(R.string.podcast_subtitle_asr_retry),
                        onStartTranscription = onStartTranscription,
                        onDownloadModel = onDownloadModel,
                    )
                }
            }

            is PodcastSubtitleState.Transcribing -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    TranscriptionProgressBar(
                        progressPercent = state.progressPercent,
                        textColor = textColor,
                        onCancel = onCancelTranscription,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 16.dp,
                                start = 32.dp,
                                end = 32.dp,
                            ),
                    )
                    if (state.cues.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        SubtitleCueList(
                            cues = state.cues,
                            activeIndex = -2,
                            translations = translations,
                            positionProvider = positionProvider,
                            showTranslation = showTranslation,
                            translationMissingCredentials = translationMissingCredentials,
                            textColor = textColor,
                            translationStatus = translationStatus,
                            onSeek = onSeek,
                            onCurrentCueChanged = onCurrentCueChanged,
                            onShareCues = onShareCues,
                            resyncToken = resyncToken,
                        )
                    }
                }
            }

            is PodcastSubtitleState.Ready -> {
                SubtitleCueList(
                    cues = state.transcript.cues,
                    activeIndex = -2,
                    translations = translations,
                    positionProvider = positionProvider,
                    showTranslation = showTranslation,
                    translationMissingCredentials = translationMissingCredentials,
                    textColor = textColor,
                    translationStatus = translationStatus,
                    onSeek = onSeek,
                    onCurrentCueChanged = onCurrentCueChanged,
                    onShareCues = onShareCues,
                    resyncToken = resyncToken,
                )
            }
        }
    }
}

@Composable
private fun SubtitleCueList(
    cues: List<SubtitleCue>,
    activeIndex: Int,
    translations: Map<Int, String>,
    positionProvider: () -> Long,
    showTranslation: Boolean,
    translationMissingCredentials: Boolean,
    textColor: Color,
    translationStatus: PodcastTranslationStatus,
    onSeek: (Long) -> Unit,
    onCurrentCueChanged: (Int) -> Unit,
    onShareCues: (List<SubtitleCue>) -> Unit,
    resyncToken: Int,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val autoScrollController = remember(scope) { SubtitleAutoScrollController(scope) }
    val autoScroll by autoScrollController.following.collectAsStateWithLifecycle()
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIndices by rememberSaveable { mutableStateOf<Set<Int>>(emptySet()) }

    LaunchedEffect(cues) {
        selectionMode = false
        selectedIndices = emptySet()
    }

    // Follow playback like the lyrics view, but pause while the user drags through the
    // transcript and resume a moment after they let go.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> autoScrollController.pause()
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    if (!selectionMode) autoScrollController.resumeAfterDrag()
                }
            }
        }
    }

    val currentIndex = when (activeIndex) {
        -2 -> {
            // Ready state: track the playing position.
            val tracked by produceState(initialValue = -1, cues) {
                while (true) {
                    val next = PodcastAsrSupport.cueIndexAt(cues, positionProvider())
                    if (next != value) {
                        value = next
                        onCurrentCueChanged(next)
                    }
                    delay(positionUpdateIntervalMs)
                }
            }
            tracked
        }
        else -> activeIndex
    }

    LaunchedEffect(resyncToken) {
        autoScrollController.resume()
        if (currentIndex >= 0 && cues.isNotEmpty()) {
            listState.animateScrollToItem(currentIndex.coerceIn(0, cues.lastIndex))
        }
    }

    LaunchedEffect(currentIndex, autoScroll, cues.size) {
        if (autoScroll && currentIndex >= 0 && cues.isNotEmpty()) {
            listState.animateScrollToItem(currentIndex.coerceIn(0, cues.lastIndex))
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.Start,
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = maxHeight * 0.34f,
                bottom = maxHeight * 0.42f,
            ),
            modifier = Modifier
                .fillMaxSize()
                .fadingEdge(top = 88.dp, bottom = 112.dp),
        ) {
            if (translationMissingCredentials && showTranslation) {
                item(key = "translation-hint") {
                    Text(
                        text = stringResource(R.string.podcast_subtitle_translation_missing_key),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            items(count = cues.size, key = { index -> index }) { index ->
                val cue = cues[index]
                SubtitleCueRow(
                    cue = cue,
                    translation = translations[index],
                    active = index == currentIndex,
                    selected = selectedIndices.contains(index),
                    showTranslation = showTranslation,
                    textColor = textColor,
                    onClick = {
                        if (selectionMode) {
                            selectedIndices = selectedIndices.toMutableSet().also {
                                if (!it.add(index)) it.remove(index)
                            }
                        } else {
                            autoScrollController.resume()
                            onSeek(cue.startMs)
                        }
                    },
                    onLongClick = {
                        selectionMode = true
                        selectedIndices = selectedIndices + index
                        autoScrollController.pause()
                    },
                )
            }
        }
        PodcastTranslationHeader(
            status = translationStatus,
            textColor = textColor,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
        )

        AnimatedVisibility(
            visible = selectionMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        selectionMode = false
                        selectedIndices = emptySet()
                        autoScrollController.resume()
                    },
                ) {
                    Icon(painterResource(R.drawable.close), stringResource(R.string.cancel))
                }
                FilledTonalButton(
                    enabled = selectedIndices.isNotEmpty(),
                    onClick = {
                        onShareCues(selectedIndices.sorted().mapNotNull(cues::getOrNull))
                        selectionMode = false
                        selectedIndices = emptySet()
                        autoScrollController.resume()
                    },
                ) {
                    Icon(painterResource(R.drawable.share), stringResource(R.string.share_selected))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
            }
        }
        if (!selectionMode && !autoScroll) {
            FilledTonalButton(
                onClick = autoScrollController::resume,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            ) {
                Icon(painterResource(R.drawable.sync), stringResource(R.string.auto_scroll))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.resync))
            }
        }
    }
}

@Composable
private fun PodcastTranslationHeader(
    status: PodcastTranslationStatus,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = status !is PodcastTranslationStatus.Idle,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val isTranslating = status == PodcastTranslationStatus.Translating
        val containerColor = when (status) {
            PodcastTranslationStatus.Translating -> MaterialTheme.colorScheme.primaryContainer
            PodcastTranslationStatus.Success -> MaterialTheme.colorScheme.tertiaryContainer
            is PodcastTranslationStatus.Error -> MaterialTheme.colorScheme.errorContainer
            PodcastTranslationStatus.Idle -> Color.Transparent
        }
        val contentColor = when (status) {
            PodcastTranslationStatus.Translating -> MaterialTheme.colorScheme.onPrimaryContainer
            PodcastTranslationStatus.Success -> MaterialTheme.colorScheme.onTertiaryContainer
            is PodcastTranslationStatus.Error -> MaterialTheme.colorScheme.onErrorContainer
            PodcastTranslationStatus.Idle -> textColor
        }
        val label = when (status) {
            PodcastTranslationStatus.Translating -> stringResource(R.string.ai_translating_subtitles)
            PodcastTranslationStatus.Success -> stringResource(R.string.ai_subtitles_translated)
            is PodcastTranslationStatus.Error -> status.message
            PodcastTranslationStatus.Idle -> ""
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isTranslating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                } else {
                    val icon = if (status == PodcastTranslationStatus.Success) R.drawable.check else R.drawable.error
                    Icon(painterResource(icon), null, modifier = Modifier.size(16.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SubtitleCueRow(
    cue: SubtitleCue,
    translation: String?,
    active: Boolean,
    selected: Boolean,
    showTranslation: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Lyrics-like emphasis, deliberately simpler: color/alpha animate, no scaling or glow.
    val lineColor by animateColorAsState(
        targetValue = if (active) textColor else textColor.copy(alpha = 0.42f),
        label = "subtitleLineColor",
    )
    Column(
        horizontalAlignment = Alignment.Start,
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .background(
                    color = when {
                        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        active -> textColor.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    },
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = cue.text,
            style =
                if (active) {
                    MaterialTheme.typography.titleMedium.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 27.sp,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                },
            color = lineColor,
            textAlign = TextAlign.Start,
        )
        if (showTranslation && !translation.isNullOrBlank()) {
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                color = lineColor.copy(alpha = if (active) 0.8f else 0.55f),
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TranscriptionProgressBar(
    progressPercent: Int,
    textColor: Color,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.podcast_subtitle_asr_running, progressPercent),
            style = MaterialTheme.typography.labelMedium,
            color = textColor.copy(alpha = 0.8f),
        )
        LinearProgressIndicator(
            progress = { progressPercent / 100f },
            modifier = Modifier.weight(1f),
            color = textColor.copy(alpha = 0.85f),
            trackColor = textColor.copy(alpha = 0.2f),
        )
        Text(
            text = stringResource(R.string.podcast_subtitle_asr_cancel),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            modifier =
                Modifier
                    .clickable(onClick = onCancel)
                    .padding(4.dp),
        )
    }
}

@Composable
private fun AsrEntry(
    asrModelReady: Boolean,
    asrModelBusy: Boolean,
    asrDownloadProgress: Pair<Long, Long>?,
    textColor: Color,
    label: String? = null,
    onStartTranscription: () -> Unit,
    onDownloadModel: () -> Unit,
) {
    when {
        asrDownloadProgress != null -> {
            val (bytes, total) = asrDownloadProgress
            val percent = if (total > 0) (bytes * 100 / total).toInt() else 0
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.podcast_subtitle_asr_model_downloading, percent),
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                LinearProgressIndicator(
                    progress = { if (total > 0) (bytes.toFloat() / total) else 0f },
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .padding(top = 8.dp),
                    color = textColor.copy(alpha = 0.85f),
                    trackColor = textColor.copy(alpha = 0.2f),
                )
            }
        }

        asrModelBusy -> {
            Text(
                text = stringResource(R.string.podcast_subtitle_asr_model_busy),
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }

        else -> {
            FilledTonalButton(
                onClick = if (asrModelReady) onStartTranscription else onDownloadModel,
            ) {
                Icon(
                    painterResource(if (asrModelReady) R.drawable.mic else R.drawable.download),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label.takeIf { asrModelReady }
                        ?: stringResource(
                            if (asrModelReady) {
                                R.string.podcast_subtitle_asr_start
                            } else {
                                R.string.podcast_subtitle_asr_download_model
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private const val positionUpdateIntervalMs = 250L

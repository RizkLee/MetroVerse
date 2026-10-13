/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import android.os.SystemClock
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.AsrRecognitionPhase
import com.metrolist.music.podcast.transcript.PodcastAsrSupport
import com.metrolist.music.podcast.transcript.PodcastSubtitleState
import com.metrolist.music.podcast.transcript.PodcastTranslationStatus
import com.metrolist.music.podcast.transcript.SubtitleCue
import com.metrolist.music.podcast.transcript.SubtitleTimingOffset
import kotlinx.coroutines.delay
import com.metrolist.music.ui.utils.fadingEdge

private val SubtitleCueSelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * Full-size podcast transcript view shown in the player's thumbnail slot (the same area the
 * inline lyrics use). Displays whole lines without truncation, keeps the active line near the top
 * with a lyrics-like highlight, and offers the on-device recognition entry point directly when
 * the episode has no transcript.
 */
@Composable
fun PodcastSubtitleView(
    onDeviceAsrSupported: Boolean,
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
    offsetMs: Int = 0,
    onSeek: (Long) -> Unit = {},
    onStartTranscription: () -> Unit = {},
    onCancelTranscription: () -> Unit = {},
    onDownloadModel: () -> Unit = {},
    onRetryTranslation: () -> Unit = {},
    onCurrentCueChanged: (Int) -> Unit = {},
    onShareCues: (List<SubtitleCue>) -> Unit = {},
    resyncToken: Int = 0,
) {
    val manager = com.metrolist.music.LocalPodcastSubtitleManager.current
    val selection = manager?.asrLanguage?.collectAsStateWithLifecycle()?.value
    val languageSupported = selection?.isSupported == true
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
                        text = if (onDeviceAsrSupported) {
                            asrModelError ?: stringResource(R.string.podcast_subtitle_unavailable)
                        } else {
                            stringResource(R.string.podcast_subtitle_unavailable)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    AsrEntry(
                        onDeviceAsrSupported = onDeviceAsrSupported,
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
                        text = if (onDeviceAsrSupported) asrModelError ?: state.message else state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    AsrEntry(
                        onDeviceAsrSupported = onDeviceAsrSupported,
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

            is PodcastSubtitleState.Transcribing, is PodcastSubtitleState.Partial, is PodcastSubtitleState.Ready -> {
                val cues = when (state) {
                    is PodcastSubtitleState.Transcribing -> state.cues
                    is PodcastSubtitleState.Partial -> state.transcript.cues
                    is PodcastSubtitleState.Ready -> state.transcript.cues
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state is PodcastSubtitleState.Transcribing) {
                        TranscriptionProgressBar(
                            progressPercent = state.progressPercent,
                            phase = state.phase,
                            textColor = textColor,
                            onCancel = onCancelTranscription,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 16.dp,
                                    start = 32.dp,
                                    end = 32.dp,
                                    bottom = 12.dp,
                                ),
                        )
                    } else if (state is PodcastSubtitleState.Partial) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(
                                top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 16.dp,
                                start = 24.dp,
                                end = 24.dp,
                                bottom = 8.dp,
                            ),
                        ) {
                            Text(
                                text = state.message ?: stringResource(R.string.podcast_subtitle_incomplete),
                                style = MaterialTheme.typography.labelMedium,
                                color = textColor.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                            )
                            if (onDeviceAsrSupported || state.hasPendingSave || state.transcript.isComplete) {
                                Spacer(Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = onStartTranscription,
                                    enabled = state.hasPendingSave || state.transcript.isComplete || (!asrModelBusy && languageSupported),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                                ) {
                                    Icon(painterResource(R.drawable.refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            if (state.hasPendingSave || state.transcript.isComplete) R.string.podcast_subtitle_retry_save
                                            else if (!state.canResume) R.string.podcast_subtitle_asr_retry
                                            else R.string.podcast_subtitle_resume,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            } else {
                                UnsupportedAsrSource(textColor)
                            }
                        }
                    }
                    if (state is PodcastSubtitleState.Ready && onDeviceAsrSupported &&
                        state.transcript.source == com.metrolist.music.podcast.transcript.TranscriptSource.ON_DEVICE &&
                        state.transcript.language != selection?.code
                    ) {
                        TextButton(onClick = onStartTranscription, enabled = languageSupported && !asrModelBusy) {
                            Text(stringResource(R.string.podcast_subtitle_asr_retry))
                        }
                    }
                    if (cues.isNotEmpty()) {
                        SubtitleCueList(
                            cues = cues,
                            translations = translations,
                            positionProvider = positionProvider,
                            offsetMs = offsetMs,
                            showTranslation = showTranslation,
                            translationMissingCredentials = translationMissingCredentials,
                            textColor = textColor,
                            translationStatus = translationStatus,
                            onSeek = onSeek,
                            onCurrentCueChanged = onCurrentCueChanged,
                            onShareCues = onShareCues,
                            onRetryTranslation = onRetryTranslation,
                            resyncToken = resyncToken,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleCueList(
    cues: List<SubtitleCue>,
    translations: Map<Int, String>,
    positionProvider: () -> Long,
    offsetMs: Int,
    showTranslation: Boolean,
    translationMissingCredentials: Boolean,
    textColor: Color,
    translationStatus: PodcastTranslationStatus,
    onSeek: (Long) -> Unit,
    onCurrentCueChanged: (Int) -> Unit,
    onShareCues: (List<SubtitleCue>) -> Unit,
    onRetryTranslation: () -> Unit,
    resyncToken: Int,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val cueIdentityCache = remember { SubtitleCueIdentityCache() }
    val keyedCues = remember(cues) { cueIdentityCache.keyedCues(cues) }
    val scope = rememberCoroutineScope()
    val autoScrollController = remember(scope) { SubtitleAutoScrollController(scope) }
    val autoScroll by autoScrollController.following.collectAsStateWithLifecycle()
    val seekPreview = remember { SubtitleSeekPreview() }
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val currentOffsetMs by rememberUpdatedState(offsetMs)
    val currentCueChanged by rememberUpdatedState(onCurrentCueChanged)
    var currentIndex by remember {
        mutableIntStateOf(PodcastAsrSupport.cueIndexAt(cues, SubtitleTimingOffset.subtitlePosition(positionProvider(), offsetMs)))
    }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedCueKeys by rememberSaveable(stateSaver = SubtitleCueSelectionSaver) {
        mutableStateOf<Set<String>>(emptySet())
    }
    val selectedCues = remember(keyedCues, selectedCueKeys) { selectedSubtitleCues(keyedCues, selectedCueKeys) }
    var translationHeaderHeightPx by remember { mutableIntStateOf(0) }
    val errorVisible = com.metrolist.music.ui.component.rememberTranslationErrorVisible(
        translationStatus, translationStatus is PodcastTranslationStatus.Error,
    )
    val visibleTranslationStatus = if (errorVisible) translationStatus else PodcastTranslationStatus.Idle

    LaunchedEffect(keyedCues) {
        selectedCueKeys = retainSubtitleCueSelection(keyedCues, selectedCueKeys)
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) autoScrollController.pause()
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

    LaunchedEffect(cues) {
        while (true) {
            currentIndex = seekPreview.index(
                PodcastAsrSupport.cueIndexAt(
                    cues,
                    SubtitleTimingOffset.subtitlePosition(currentPositionProvider(), currentOffsetMs),
                ),
                cues.size,
                SystemClock.elapsedRealtime(),
                cueIndexForKey = { key -> keyedCues.indexOfFirst { it.key == key } },
            )
            currentCueChanged(currentIndex)
            delay(positionUpdateIntervalMs)
        }
    }

    LaunchedEffect(resyncToken) {
        if (!selectionMode) autoScrollController.resume()
    }

    val hintCount = if (translationMissingCredentials && showTranslation) 1 else 0
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val safeTop = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 12.dp
        val anchorTop = with(density) {
            subtitleAnchorTopPx(
                viewportHeightPx = maxHeight.toPx(),
                activeLineHeightPx = 27.sp.toPx(),
                safeTopPx = safeTop.toPx(),
                headerHeightPx = if (visibleTranslationStatus is PodcastTranslationStatus.Idle) 0f else translationHeaderHeightPx.toFloat(),
                headerGapPx = 12.dp.toPx(),
            ).toDp()
        }
        LaunchedEffect(currentIndex, autoScroll, selectionMode, cues.size, resyncToken, hintCount, anchorTop) {
            if (autoScroll && !selectionMode && currentIndex >= 0 && cues.isNotEmpty()) {
                listState.animateScrollToItem(currentIndex.coerceIn(0, cues.lastIndex) + hintCount)
            }
        }
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.Start,
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = anchorTop,
                bottom = (maxHeight - anchorTop).coerceAtLeast(0.dp),
            ),
            modifier = Modifier
                .fillMaxSize()
                .fadingEdge(
                    top = minOf(130.dp, anchorTop),
                    bottom = minOf(160.dp, maxHeight * 0.35f),
                ),
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
            itemsIndexed(items = keyedCues, key = { _, item -> item.key }) { index, item ->
                val cue = item.cue
                SubtitleCueRow(
                    cue = cue,
                    translation = translations[index],
                    active = index == currentIndex,
                    selected = item.key in selectedCueKeys,
                    showTranslation = showTranslation,
                    textColor = textColor,
                    onClick = {
                        if (selectionMode) {
                            selectedCueKeys = selectedCueKeys.toMutableSet().also {
                                if (!it.add(item.key)) it.remove(item.key)
                            }
                        } else {
                            seekPreview.request(index, SystemClock.elapsedRealtime(), item.key)
                            currentIndex = index
                            currentCueChanged(index)
                            autoScrollController.resume()
                            onSeek(SubtitleTimingOffset.playbackPosition(cue.startMs, offsetMs))
                        }
                    },
                    onLongClick = {
                        selectionMode = true
                        selectedCueKeys = selectedCueKeys + item.key
                        autoScrollController.pause()
                    },
                )
            }
        }
        PodcastTranslationHeader(
            status = visibleTranslationStatus,
            textColor = textColor,
            onRetry = onRetryTranslation,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = safeTop, start = 24.dp, end = 24.dp)
                .onSizeChanged { translationHeaderHeightPx = it.height },
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
                        selectedCueKeys = emptySet()
                        autoScrollController.resume()
                    },
                ) {
                    Icon(painterResource(R.drawable.close), stringResource(R.string.cancel))
                }
                FilledTonalButton(
                    enabled = selectedCues.isNotEmpty(),
                    onClick = {
                        onShareCues(selectedCues)
                        selectionMode = false
                        selectedCueKeys = emptySet()
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
    onRetry: () -> Unit,
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
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
                if (status is PodcastTranslationStatus.Error) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
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
                .heightIn(min = 48.dp)
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
    phase: AsrRecognitionPhase,
    textColor: Color,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusLabel = when (phase) {
        AsrRecognitionPhase.PREPARING -> stringResource(R.string.podcast_subtitle_asr_preparing) + " $progressPercent%"
        AsrRecognitionPhase.RESUMING -> stringResource(R.string.podcast_subtitle_asr_resuming) + " $progressPercent%"
        AsrRecognitionPhase.RECOGNIZING -> stringResource(R.string.podcast_subtitle_asr_running, progressPercent)
    }
    val cancelLabel = stringResource(R.string.podcast_subtitle_asr_cancel)
    val labelStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minimumProgressWidth = 48.dp
    val rowSpacing = 10.dp
    val cancelPadding = 4.dp
    BoxWithConstraints(modifier = modifier) {
        val textWidthPx = textMeasurer.measure(statusLabel, labelStyle, softWrap = false).size.width +
            textMeasurer.measure(cancelLabel, labelStyle, softWrap = false).size.width
        val controlsWidthPx = with(density) {
            minimumProgressWidth.roundToPx() + rowSpacing.roundToPx() * 2 + cancelPadding.roundToPx() * 2
        }
        val inlineStatus = phase == AsrRecognitionPhase.RECOGNIZING &&
            textWidthPx + controlsWidthPx <= constraints.maxWidth
        val maximumCancelWidth = (maxWidth - minimumProgressWidth - rowSpacing).coerceAtLeast(0.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!inlineStatus) {
                Text(
                    text = statusLabel,
                    style = labelStyle,
                    color = textColor.copy(alpha = 0.8f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(rowSpacing),
            ) {
                if (inlineStatus) {
                    Text(
                        text = statusLabel,
                        style = labelStyle,
                        color = textColor.copy(alpha = 0.8f),
                    )
                }
                LinearProgressIndicator(
                    progress = { progressPercent / 100f },
                    modifier = Modifier.weight(1f),
                    color = textColor.copy(alpha = 0.85f),
                    trackColor = textColor.copy(alpha = 0.2f),
                )
                Text(
                    text = cancelLabel,
                    style = labelStyle,
                    color = textColor,
                    modifier = Modifier
                        .widthIn(max = maximumCancelWidth)
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(cancelPadding),
                )
            }
        }
    }
}

@Composable
private fun UnsupportedAsrSource(textColor: Color) {
    Text(
        text = stringResource(R.string.podcast_subtitle_asr_source_unsupported),
        style = MaterialTheme.typography.bodyMedium,
        color = textColor.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun AsrEntry(
    onDeviceAsrSupported: Boolean,
    asrModelReady: Boolean,
    asrModelBusy: Boolean,
    asrDownloadProgress: Pair<Long, Long>?,
    textColor: Color,
    label: String? = null,
    onStartTranscription: () -> Unit,
    onDownloadModel: () -> Unit,
) {
    when {
        !onDeviceAsrSupported -> UnsupportedAsrSource(textColor)

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
            val manager = com.metrolist.music.LocalPodcastSubtitleManager.current
            val selection = manager?.asrLanguage?.collectAsStateWithLifecycle()?.value
            var showLanguageDialog by remember { mutableStateOf(false) }
            com.metrolist.music.ui.component.PodcastAsrActionButton(
                selection = selection,
                modelReady = asrModelReady,
                actionLabel = label.takeIf { asrModelReady } ?: stringResource(
                    if (asrModelReady) R.string.podcast_subtitle_asr_start else R.string.podcast_subtitle_asr_download_model,
                ),
                onAction = if (asrModelReady) onStartTranscription else onDownloadModel,
                onSelectLanguage = { showLanguageDialog = true },
            )
            if (showLanguageDialog && selection != null) {
                com.metrolist.music.ui.component.AsrLanguageDialog(selection, { showLanguageDialog = false }, manager::selectAsrLanguage)
            }
        }
    }
}

private const val positionUpdateIntervalMs = 250L

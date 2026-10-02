/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.PodcastAsrSupport
import com.metrolist.music.podcast.transcript.PodcastSubtitleState
import com.metrolist.music.podcast.transcript.SubtitleCue
import kotlinx.coroutines.delay

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
    modifier: Modifier = Modifier,
    asrDownloadProgress: Pair<Long, Long>? = null,
    onSeek: (Long) -> Unit = {},
    onStartTranscription: () -> Unit = {},
    onCancelTranscription: () -> Unit = {},
    onDownloadModel: () -> Unit = {},
    onCurrentCueChanged: (Int) -> Unit = {},
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
                        text = stringResource(R.string.podcast_subtitle_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    AsrEntry(
                        asrModelReady = true,
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
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    AsrEntry(
                        asrModelReady = true,
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
                            .padding(horizontal = 32.dp),
                    )
                    if (state.cues.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        SubtitleCueList(
                            cues = state.cues,
                            activeIndex = state.cues.lastIndex,
                            translations = translations,
                            positionProvider = positionProvider,
                            showTranslation = showTranslation,
                            translationMissingCredentials = translationMissingCredentials,
                            textColor = textColor,
                            onSeek = onSeek,
                            onCurrentCueChanged = onCurrentCueChanged,
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
                    onSeek = onSeek,
                    onCurrentCueChanged = onCurrentCueChanged,
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
    onSeek: (Long) -> Unit,
    onCurrentCueChanged: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var autoScroll by rememberSaveable { mutableStateOf(true) }

    // Follow playback like the lyrics view, but pause while the user drags through the
    // transcript and resume a moment after they let go.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> autoScroll = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    delay(6_000)
                    autoScroll = true
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

    LaunchedEffect(currentIndex, autoScroll, cues.size) {
        if (autoScroll && currentIndex >= 0 && cues.isNotEmpty()) {
            listState.animateScrollToItem(currentIndex.coerceIn(0, cues.lastIndex))
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                horizontal = 24.dp,
                vertical = maxHeight * 0.32f,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (translationMissingCredentials && showTranslation) {
                item(key = "translation-hint") {
                    Text(
                        text = stringResource(R.string.podcast_subtitle_translation_missing_key),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
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
                    showTranslation = showTranslation,
                    textColor = textColor,
                    onClick = { onSeek(cue.startMs) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleCueRow(
    cue: SubtitleCue,
    translation: String?,
    active: Boolean,
    showTranslation: Boolean,
    textColor: Color,
    onClick: () -> Unit,
) {
    // Lyrics-like emphasis, deliberately simpler: color/alpha animate, no scaling or glow.
    val lineColor by animateColorAsState(
        targetValue = if (active) textColor else textColor.copy(alpha = 0.42f),
        label = "subtitleLineColor",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
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
            textAlign = TextAlign.Center,
        )
        if (showTranslation && !translation.isNullOrBlank()) {
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                color = lineColor.copy(alpha = if (active) 0.8f else 0.55f),
                textAlign = TextAlign.Center,
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

        else -> {
            TextButton(onClick = if (asrModelReady) onStartTranscription else onDownloadModel) {
                Text(
                    text = label
                        ?: stringResource(
                            if (asrModelReady) {
                                R.string.podcast_subtitle_asr_start
                            } else {
                                R.string.podcast_subtitle_asr_download_model
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                )
            }
        }
    }
}

private const val positionUpdateIntervalMs = 250L

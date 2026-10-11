/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import android.text.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.AiSystemPromptKey
import com.metrolist.music.constants.AlwaysUseAiTranslationKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.DeeplFormalityKey
import com.metrolist.music.constants.LyricsClickKey
import com.metrolist.music.constants.LyricsRomanizeAsMainKey
import com.metrolist.music.constants.LyricsRomanizeCyrillicByLineKey
import com.metrolist.music.constants.LyricsRomanizeList
import com.metrolist.music.constants.LyricsTextPositionKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterDefaultBaseUrl
import com.metrolist.music.constants.OpenRouterDefaultModel
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.RespectAgentPositioningKey
import com.metrolist.music.constants.ShowIntervalIndicatorKey
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.constants.TranslateModeKey
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsResyncHelper
import com.metrolist.music.lyrics.LyricsTranslationHelper
import com.metrolist.music.lyrics.hasMatchingStoredLyricsTranslation
import com.metrolist.music.lyrics.shouldRequestAutomaticLyricsTranslation
import com.metrolist.music.lyrics.verifiedLyricsSongId
import com.metrolist.music.lyrics.LyricsUtils.findActiveLineIndices
import com.metrolist.music.lyrics.lyricsTextLooksSynced
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.ui.screens.settings.LyricsPosition
import com.metrolist.music.ui.utils.defaultRomanizationLanguageSettings
import com.metrolist.music.ui.utils.fadingEdge
import com.metrolist.music.ui.utils.rememberEffectiveAppLanguageCode
import com.metrolist.music.utils.ComposeToImage
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LyricsViewModel
import com.metrolist.music.viewmodels.processedLyricsBelongToCurrentMedia
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LYRICS_ANCHOR_RATIO = 0.35f
private val LYRICS_ITEM_FALLBACK_HEIGHT_DP = 68.dp
private val LYRICS_ITEM_GAP_DP = 16.dp
private val LYRICS_FADE_TOP_DP = 130.dp
private val LYRICS_FADE_BOTTOM_DP = 160.dp
private val LYRICS_SCROLL_TOP_PADDING_DP = 100.dp
private val LYRICS_SCROLL_BOTTOM_PADDING_DP = 130.dp

/**
 * Scroll clamp for the lyrics list.
 *
 * Item i sits at y = anchorY + positions[i] + offset, where positions are relative to the
 * active line (positions[active] == 0). `contentAbove` is the stacked height of every item
 * above the active line (-positions[0]); `contentBelow` is positions[last] (active through
 * the item before the last).
 *
 * - maxOffset (drag toward the beginning) stops with the first item's top at [topPadding].
 * - minOffset (drag toward the end) stops with the last item's bottom at viewport-[bottomPadding].
 *
 * These two are the only legal extremes. Auto-scroll does NOT rest at 0: near either end of
 * the song the rest offset is whatever keeps the first/last line inside the padded viewport
 * (see [computeAutoRestOffset]); forcing 0 here is what left a blank slab past the last line.
 */
internal data class LyricsScrollBounds(val minOffset: Float, val maxOffset: Float) {
    val clampMin: Float get() = minOf(minOffset, maxOffset)
    val clampMax: Float get() = maxOf(minOffset, maxOffset)
}

internal fun computeLyricsScrollBounds(
    contentAbove: Float,
    contentBelow: Float,
    lastItemHeight: Float,
    viewportHeight: Float,
    anchorY: Float,
    topPadding: Float,
    bottomPadding: Float,
): LyricsScrollBounds {
    val maxOffset = topPadding - anchorY + contentAbove
    val minOffset = viewportHeight - bottomPadding - lastItemHeight - anchorY - contentBelow
    return LyricsScrollBounds(minOffset = minOffset, maxOffset = maxOffset)
}

/**
 * Offset auto-scroll should rest at for the current active line.
 *
 * Mid-song this is 0 (active line on the anchor). Near the end it shifts so the last line
 * sits on the bottom padding instead of leaving blank space below. Near the start it shifts
 * so the first line sits on the top padding.
 */
internal fun computeAutoRestOffset(
    contentAbove: Float,
    contentBelow: Float,
    lastItemHeight: Float,
    viewportHeight: Float,
    anchorY: Float,
    topPadding: Float,
    bottomPadding: Float,
): Float {
    val bounds = computeLyricsScrollBounds(
        contentAbove = contentAbove,
        contentBelow = contentBelow,
        lastItemHeight = lastItemHeight,
        viewportHeight = viewportHeight,
        anchorY = anchorY,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
    )
    val firstY0 = anchorY - contentAbove
    val lastBottom0 = anchorY + contentBelow + lastItemHeight
    val bottomLimit = viewportHeight - bottomPadding
    val rest = when {
        // Not enough content below the anchor: pin the last line to the bottom padding.
        lastBottom0 < bottomLimit -> bottomLimit - lastBottom0
        // Not enough content above the anchor: pin the first line to the top padding.
        firstY0 > topPadding -> topPadding - firstY0
        else -> 0f
    }
    return rest.coerceIn(bounds.clampMin, bounds.clampMax)
}
private const val LYRICS_STAGGER_DELAY_PER_DISTANCE = 20
private const val LYRICS_STAGGER_DELAY_MAX_MS = 200
private const val LYRICS_PREVIEW_TIME = 8000L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@SuppressLint("UnusedBoxWithConstraintsScope", "StringFormatInvalid")
@Composable
fun ExperimentalLyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyrics: Boolean,
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
    playbackPositionProvider: (() -> Long)? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val appLanguageCode = rememberEffectiveAppLanguageCode()
    val defaultRomanizationList = remember(appLanguageCode) { defaultRomanizationLanguageSettings(appLanguageCode) }
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    val lyricsTextPosition by rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.CENTER)
    val changeLyrics by rememberPreference(LyricsClickKey, true)
    val romanizeLyricsList = rememberPreference(LyricsRomanizeList, "")
    val romanizeAsMain by rememberPreference(LyricsRomanizeAsMainKey, false)
    val romanizeCyrillicByLine by rememberPreference(LyricsRomanizeCyrillicByLineKey, false)
    val respectAgentPositioning by rememberPreference(RespectAgentPositioningKey, true)
    val showIntervalIndicator by rememberPreference(ShowIntervalIndicatorKey, true)
    
    // AI Translation Preferences
    val openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    val deeplApiKey by rememberPreference(DeeplApiKey, "")
    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, OpenRouterDefaultBaseUrl)
    val openRouterModel by rememberPreference(OpenRouterModelKey, OpenRouterDefaultModel)
    val translateLanguage by rememberPreference(TranslateLanguageKey, appLanguageCode)
    val translateMode by rememberPreference(TranslateModeKey, "Literal")
    val deeplFormality by rememberPreference(DeeplFormalityKey, "default")
    val aiSystemPrompt by rememberPreference(AiSystemPromptKey, "")
    val alwaysUseAiTranslation by rememberPreference(AlwaysUseAiTranslationKey, false)
    
    val scope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val translationStatus by LyricsTranslationHelper.status.collectAsStateWithLifecycle()
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    var lastValidLyricsEntity by remember { mutableStateOf<com.metrolist.music.db.entities.LyricsEntity?>(null) }
    
    LaunchedEffect(currentLyricsEntity) {
        if (currentLyricsEntity != null) {
            lastValidLyricsEntity = currentLyricsEntity
        }
    }
    
    val lyricsEntity = remember(currentLyricsEntity, translationStatus) {
        if (currentLyricsEntity != null) {
            currentLyricsEntity
        } else if (translationStatus is LyricsTranslationHelper.TranslationStatus.Translating || translationStatus is LyricsTranslationHelper.TranslationStatus.Success) {
            lastValidLyricsEntity
        } else {
            null
        }
    }
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val lyrics = remember(lyricsEntity) { lyricsEntity?.lyrics?.trim() }

    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.BLUR
    )

    val enabledLanguages = remember(romanizeLyricsList.value, defaultRomanizationList) {
        if (romanizeLyricsList.value.isEmpty()) {
            defaultRomanizationList
        } else {
            romanizeLyricsList.value.split(",").map { entry ->
                val (lang, checked) = entry.split(":")
                Pair(lang, checked.toBoolean())
            }
        }.filter { it.second }.map { it.first }
    }

    val processedLyricsState by lyricsViewModel.processedLyricsState.collectAsStateWithLifecycle()
    val processedLyricsMatchesCurrent =
        processedLyricsBelongToCurrentMedia(
            processedSourceLyrics = processedLyricsState.sourceLyrics,
            currentLyrics = lyrics,
            lyricsEntityId = lyricsEntity?.id,
            mediaId = mediaMetadata?.id,
        )
    val lines = if (processedLyricsMatchesCurrent) processedLyricsState.lines else emptyList()
    val mergedLyricsList =
        if (processedLyricsMatchesCurrent) processedLyricsState.mergedLyricsList else emptyList()

    LaunchedEffect(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator) {
        lyricsViewModel.processLyrics(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator)
    }

    val isSynced = remember(lyrics) { lyricsTextLooksSynced(lyrics) }
    val hasWordTimings = remember(lines) { lines.any { it.words?.isNotEmpty() == true } }

    DisposableEffect(Unit) {
        LyricsTranslationHelper.setCompositionActive(true)
        onDispose {
            LyricsTranslationHelper.setCompositionActive(false)
            LyricsTranslationHelper.cancelTranslation()
        }
    }
    
    LaunchedEffect(lines, lyricsEntity, translateLanguage, translateMode) {
        if (lines.isNotEmpty() && lyricsEntity != null) {
            LyricsTranslationHelper.loadTranslationsFromDatabase(
                lyrics = lines,
                lyricsEntity = lyricsEntity,
                targetLanguage = translateLanguage,
                mode = translateMode
            )
        }
    }

    LaunchedEffect(
        alwaysUseAiTranslation,
        showLyrics,
        lines,
        lyricsEntity,
        aiProvider,
        openRouterApiKey,
        deeplApiKey,
        openRouterBaseUrl,
        openRouterModel,
        translateLanguage,
        translateMode,
        deeplFormality,
        aiSystemPrompt,
        mediaMetadata?.id,
    ) {
        val mediaSongId = mediaMetadata?.id.orEmpty()
        LyricsTranslationHelper.updateCurrentLyricsSong(mediaSongId)
        if (!showLyrics || !alwaysUseAiTranslation) {
            LyricsTranslationHelper.cancelAutomaticTranslation()
            return@LaunchedEffect
        }
        LyricsTranslationHelper.cancelAutomaticTranslationForOtherSong(mediaSongId)

        val effectiveApiKey = if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey
        val songId = verifiedLyricsSongId(mediaSongId, lyricsEntity?.id).orEmpty()
        val requiredLineCount = lines.count { it.text.isNotBlank() }
        val hasMatchingStoredTranslation =
            hasMatchingStoredLyricsTranslation(
                lyricsEntity = lyricsEntity,
                targetLanguage = translateLanguage,
                mode = translateMode,
                requiredLineCount = requiredLineCount,
            )
        if (
            shouldRequestAutomaticLyricsTranslation(
                alwaysUseAiTranslation = alwaysUseAiTranslation,
                showLyrics = showLyrics,
                hasLyrics = requiredLineCount > 0,
                hasApiKey = effectiveApiKey.isNotBlank(),
                hasVerifiedSongId = songId.isNotBlank(),
                hasMatchingStoredTranslation = hasMatchingStoredTranslation,
                translationInProgress = LyricsTranslationHelper.isTranslationInProgress(),
                automaticTranslationSuppressed =
                    LyricsTranslationHelper.isAutomaticTranslationSuppressed(songId),
            )
        ) {
            LyricsTranslationHelper.translateLyrics(
                lyrics = lines,
                targetLanguage = translateLanguage,
                apiKey = openRouterApiKey,
                baseUrl = openRouterBaseUrl,
                model = openRouterModel,
                mode = translateMode,
                scope = scope,
                context = context,
                provider = aiProvider,
                deeplApiKey = deeplApiKey,
                deeplFormality = deeplFormality,
                useStreaming = true,
                songId = songId,
                database = database,
                systemPrompt = aiSystemPrompt,
                automaticRequest = true,
            )
        }
    }
    
    LaunchedEffect(
        showLyrics, 
        lines, 
        aiProvider, 
        openRouterApiKey, 
        deeplApiKey, 
        openRouterBaseUrl, 
        openRouterModel, 
        translateLanguage, 
        translateMode,
        deeplFormality,
        aiSystemPrompt,
        currentSong,
        database
    ) {
        LyricsTranslationHelper.manualTrigger.collectLatest {
            val effectiveApiKey = if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey
            if (showLyrics && lines.isNotEmpty() && effectiveApiKey.isNotBlank()) {
                LyricsTranslationHelper.translateLyrics(
                    lyrics = lines,
                    targetLanguage = translateLanguage,
                    apiKey = openRouterApiKey,
                    baseUrl = openRouterBaseUrl,
                    model = openRouterModel,
                    mode = translateMode,
                    scope = scope,
                    context = context,
                    provider = aiProvider,
                    deeplApiKey = deeplApiKey,
                    deeplFormality = deeplFormality,
                    useStreaming = true,
                    songId = currentSong?.id ?: "",
                    database = database,
                    systemPrompt = aiSystemPrompt,
                )
            } else if (effectiveApiKey.isBlank()) {
                Toast.makeText(context, context.getString(R.string.ai_api_key_required), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    LaunchedEffect(lines) {
        LyricsTranslationHelper.clearTranslationsTrigger.collectLatest {
            lines.forEach { it.translatedTextFlow.value = null }
        }
    }

    val expressiveAccent = when (playerBackground) {
        PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.primary
        PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> Color.White
    }

    var activeLineIndices by remember { mutableStateOf(emptySet<Int>()) }
    var scrollTargetIndex by rememberSaveable { mutableIntStateOf(-1) }
    var previousScrollActiveIndices by remember { mutableStateOf(emptySet<Int>()) }
    
    var currentPositionState by remember { mutableLongStateOf(0L) }
    var deferredCurrentLineIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastPreviewTime by rememberSaveable { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val isLyricsProviderShown = lyricsEntity != null && lyricsEntity.provider != "Unknown" && lyricsEntity.provider != "Manual" && !isSelectionModeActive
    var isAutoScrollEnabled by rememberSaveable { mutableStateOf(true) }
    var pendingSeek by remember(lyrics) { mutableStateOf<LyricsSeekTarget?>(null) }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    val maxSelectionLimit = 5
    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast.makeText(context, context.getString(R.string.max_selection_limit, maxSelectionLimit), Toast.LENGTH_SHORT).show()
            showMaxSelectionToast = false
        }
    }

    var lastMainMaxSeen by remember(lyrics, lines) { mutableIntStateOf(-1) }
    var smoothPositionForSync by remember { mutableLongStateOf(0L) }
    val latestPlaybackPositionProvider by rememberUpdatedState(playbackPositionProvider)
    val latestSliderPositionProvider by rememberUpdatedState(sliderPositionProvider)

    LaunchedEffect(lyrics, lines) {
        if (lyrics.isNullOrEmpty() || lines.isEmpty()) {
            activeLineIndices = emptySet()
            return@LaunchedEffect
        }
        
        var lastPlayerPos = latestPlaybackPositionProvider?.invoke() ?: playerConnection.player.currentPosition
        var lastUpdateTime = System.currentTimeMillis()
        val mainLineIndices = lines.indices.filter { !lines[it].isBackground }.toSet()
        
        while (isActive) {
            withFrameNanos { _ -> }
            val now = System.currentTimeMillis()
            val sliderPosition = latestSliderPositionProvider()
            val previousPlayerPos = lastPlayerPos
            val playerPos = latestPlaybackPositionProvider?.invoke() ?: playerConnection.player.currentPosition
            if (playerPos != lastPlayerPos) {
                lastPlayerPos = playerPos
                lastUpdateTime = now
            }
            val observedPosition = sliderPosition
                ?: (lastPlayerPos + if (playerConnection.player.isPlaying) now - lastUpdateTime else 0)
            
            val lyricsOffset = currentSong?.song?.lyricsOffset ?: 0
            val playback = resolveLyricsPlaybackPosition(
                observedPositionMs = observedPosition,
                previewPositionMs = sliderPosition,
                observedIndex = pendingSeek?.index?.takeIf {
                    it in findActiveLineIndices(lines, observedPosition + lyricsOffset)
                } ?: -1,
                requestedSeek = pendingSeek,
                nowMs = SystemClock.uptimeMillis(),
            )
            val waitingForSeek = playback.pendingSeek
            pendingSeek = waitingForSeek
            isSeeking = playback.isSeeking
            val position = playback.positionMs
            currentPositionState = position
            smoothPositionForSync = position
            
            val effectivePosition = position + lyricsOffset
            
            val initialActiveIndices = findActiveLineIndices(lines, effectivePosition)
            val scrollActiveIndicesRaw = findActiveLineIndices(lines, effectivePosition + (if (hasWordTimings) 0L else 250L))
            
            val scrollActiveIndices = scrollActiveIndicesRaw.toMutableSet()
            for (i in scrollActiveIndicesRaw) {
                if (lines.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (lines.getOrNull(j)?.isBackground == false) {
                            scrollActiveIndices.add(j)
                            break
                        }
                    }
                }
            }
            
            val newActiveIndices = initialActiveIndices.toMutableSet()
            for (i in initialActiveIndices) {
                if (lines.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (lines.getOrNull(j)?.isBackground == false) {
                            newActiveIndices.add(j)
                            break
                        }
                    }
                }
            }

            val resetAnchor = isSeeking || playerPos < previousPlayerPos
            val scrollState = resolveLyricsScrollTarget(
                state = LyricsScrollTargetState(scrollTargetIndex, lastMainMaxSeen, previousScrollActiveIndices),
                activeIndices = scrollActiveIndices,
                mainLineIndices = mainLineIndices,
                previousPlaybackPositionMs = previousPlayerPos,
                playbackPositionMs = playerPos,
                isSeeking = isSeeking,
                pendingSeekIndex = waitingForSeek?.index,
                fallbackAnchorIndex = if (resetAnchor && scrollActiveIndices.isEmpty()) {
                    findLyricsScrollAnchor(lines, effectivePosition)
                } else {
                    -1
                },
            )
            scrollTargetIndex = scrollState.index
            lastMainMaxSeen = scrollState.lastMainIndex
            previousScrollActiveIndices = scrollState.previousActiveIndices
            activeLineIndices = newActiveIndices
        }
    }

    LaunchedEffect(isSeeking, lastPreviewTime) {
        if (isSeeking) {
            lastPreviewTime = 0L
        } else if (lastPreviewTime != 0L) {
            delay(LYRICS_PREVIEW_TIME)
            lastPreviewTime = 0L
        }
    }

    LaunchedEffect(scrollTargetIndex, isAutoScrollEnabled) {
        if (scrollTargetIndex != -1 && isAutoScrollEnabled) {
            deferredCurrentLineIndex = scrollTargetIndex
        }
    }

    var userManualOffset by remember { mutableFloatStateOf(0f) }
    var directScrollRequest by remember { mutableIntStateOf(0) }
    var isDirectScroll by remember { mutableStateOf(false) }
    LaunchedEffect(lyrics, lines) {
        isAutoScrollEnabled = true
        userManualOffset = 0f
        isDirectScroll = false
        scrollTargetIndex = -1
        deferredCurrentLineIndex = 0
        isSelectionModeActive = false
        selectedIndices.clear()
        previousScrollActiveIndices = emptySet()
    }
    
    var flingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val velocityTracker = remember { VelocityTracker() }
    val decayAnimSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }
    val itemHeights = remember(lyrics, mergedLyricsList) { mutableStateMapOf<Int, Int>() }
    var isInitialLayout by remember(lyrics, mergedLyricsList) { mutableStateOf(true) }

    val activeListIndex by remember(mergedLyricsList, deferredCurrentLineIndex) {
        derivedStateOf {
            resolveLyricsListAnchorIndex(mergedLyricsList, deferredCurrentLineIndex)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(showLyrics) {
        val activity = context as? Activity
        if (showLyrics) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize().padding(bottom = 12.dp)
    ) {
        val maxHeightPx = constraints.maxHeight.toFloat()
        val anchorY = maxHeightPx * LYRICS_ANCHOR_RATIO
        val lineHeightPx = with(density) { LYRICS_ITEM_FALLBACK_HEIGHT_DP.toPx() }
        val indicatorHeightPx = with(density) { 72.dp.toPx() }
        
        // Use a more permissive fallback for constraints to prevent "locks" if items are not measured yet
        val constraintLineHeightPx = with(density) { 120.dp.toPx() }
        // Single height resolver so positions and scroll clamps agree on unmeasured items.
        // Prefer the average of already-measured heights so translated/romanized lines
        // (which are taller) do not get an underestimated fallback while they stream in.
        val resolveItemHeight: (Int) -> Float = remember(itemHeights.toMap(), mergedLyricsList) {
            val measuredAverage = if (itemHeights.isEmpty()) {
                null
            } else {
                itemHeights.values.average().toFloat().coerceAtLeast(lineHeightPx)
            }
            { index: Int ->
                itemHeights[index]?.toFloat()
                    ?: measuredAverage
                    ?: if (mergedLyricsList.getOrNull(index) is LyricsListItem.Indicator) {
                        indicatorHeightPx
                    } else {
                        constraintLineHeightPx
                    }
            }
        }

        val positions = remember(itemHeights.toMap(), activeListIndex, mergedLyricsList, density) {
            val gapPx = with(density) { LYRICS_ITEM_GAP_DP.toPx() }
            computeLyricsItemPositions(
                itemHeights = mergedLyricsList.indices.map(resolveItemHeight),
                gapsBeforeItems = mergedLyricsList.map { item ->
                    if ((item as? LyricsListItem.Line)?.entry?.isBackground == true || item is LyricsListItem.Indicator) 0f else gapPx
                },
                anchorIndex = activeListIndex,
            )
        }

        val scrollLayout = remember(itemHeights.toMap(), positions, mergedLyricsList, activeListIndex, anchorY, maxHeightPx, density) {
            if (mergedLyricsList.isEmpty() || activeListIndex == -1) {
                LyricsScrollBounds(minOffset = 0f, maxOffset = 0f) to 0f
            } else {
                val totalAbove = -(positions[0] ?: 0f)
                val totalBelow = positions[mergedLyricsList.lastIndex] ?: 0f
                val lastHeight = resolveItemHeight(mergedLyricsList.size - 1)
                val topPad = with(density) { LYRICS_SCROLL_TOP_PADDING_DP.toPx() }
                val bottomPad = with(density) { LYRICS_SCROLL_BOTTOM_PADDING_DP.toPx() }
                val bounds = computeLyricsScrollBounds(
                    contentAbove = totalAbove,
                    contentBelow = totalBelow,
                    lastItemHeight = lastHeight,
                    viewportHeight = maxHeightPx,
                    anchorY = anchorY,
                    topPadding = topPad,
                    bottomPadding = bottomPad,
                )
                val rest = computeAutoRestOffset(
                    contentAbove = totalAbove,
                    contentBelow = totalBelow,
                    lastItemHeight = lastHeight,
                    viewportHeight = maxHeightPx,
                    anchorY = anchorY,
                    topPadding = topPad,
                    bottomPadding = bottomPad,
                )
                bounds to rest
            }
        }
        val lyricsScrollBounds = scrollLayout.first
        val autoRestOffset = scrollLayout.second

        // Manual scroll is clamped to the real first/last lyric positions only. Auto-scroll
        // rests at autoRestOffset (0 mid-song; pinned to the last/first line near either end)
        // instead of always 0, which used to leave a blank slab past the last line.
        val manualScrollBounds = LyricsScrollBounds(
            lyricsScrollBounds.minOffset - autoRestOffset,
            lyricsScrollBounds.maxOffset - autoRestOffset,
        )
        val scrollClampMin = manualScrollBounds.clampMin
        val scrollClampMax = manualScrollBounds.clampMax
        // The pointer coroutine survives layout updates, including translation height changes.
        val latestScrollBounds by rememberUpdatedState(manualScrollBounds)

        LaunchedEffect(scrollClampMin, scrollClampMax, isAutoScrollEnabled) {
            if (!isAutoScrollEnabled && (userManualOffset < scrollClampMin || userManualOffset > scrollClampMax)) {
                userManualOffset = userManualOffset.coerceIn(scrollClampMin, scrollClampMax)
            }
        }

        fun restOffsetForAnchor(listIndex: Int): Float {
            val shift = positions[listIndex] ?: 0f
            return computeAutoRestOffset(
                contentAbove = shift - (positions[0] ?: 0f),
                contentBelow = (positions[mergedLyricsList.lastIndex] ?: 0f) - shift,
                lastItemHeight = resolveItemHeight(mergedLyricsList.lastIndex),
                viewportHeight = maxHeightPx,
                anchorY = anchorY,
                topPadding = with(density) { LYRICS_SCROLL_TOP_PADDING_DP.toPx() },
                bottomPadding = with(density) { LYRICS_SCROLL_BOTTOM_PADDING_DP.toPx() },
            )
        }

        LaunchedEffect(isAutoScrollEnabled, lines, directScrollRequest) {
            if (isAutoScrollEnabled) {
                val start = userManualOffset
                val target = 0f
                if (abs(start - target) < 1f) {
                    userManualOffset = target
                    withFrameNanos { }
                    isDirectScroll = false
                    return@LaunchedEffect
                }
                val anim = Animatable(start)
                var lastValue = start
                anim.animateTo(target, tween((abs(start - target) / 4f).toInt().coerceIn(200, 600), easing = FastOutSlowInEasing)) {
                    userManualOffset += (value - lastValue)
                    lastValue = value
                }
                userManualOffset = target
                isDirectScroll = false
            }
        }

        LaunchedEffect(showLyrics, lyrics, mergedLyricsList.size) {
            if (showLyrics && mergedLyricsList.isNotEmpty()) {
                isInitialLayout = true
                snapshotFlow { 
                    val h = itemHeights.toMap()
                    val windowStart = (activeListIndex - 8).coerceAtLeast(0)
                    val windowEnd = (activeListIndex + 12).coerceAtMost(mergedLyricsList.size - 1)
                    (windowStart..windowEnd).all { h.containsKey(it) } 
                }.first { it }
                isInitialLayout = false
            }
        }

        // When jumping significantly, we might want to reset isInitialLayout to prevent jumps
        LaunchedEffect(activeListIndex) {
            if (mergedLyricsList.isNotEmpty()) {
                val h = itemHeights.toMap()
                val windowStart = (activeListIndex - 3).coerceAtLeast(0)
                val windowEnd = (activeListIndex + 3).coerceAtMost(mergedLyricsList.size - 1)
                val needsMeasurement = (windowStart..windowEnd).any { !h.containsKey(it) }
                if (needsMeasurement) {
                    isInitialLayout = true
                    snapshotFlow { 
                        val hh = itemHeights.toMap()
                        (windowStart..windowEnd).all { hh.containsKey(it) } 
                    }.first { it }
                    isInitialLayout = false
                }
            }
        }

        val latestShowLyrics by rememberUpdatedState(showLyrics)
        val latestResyncLyrics by rememberUpdatedState(
            newValue = {
                flingJob?.cancel()
                val target = findLyricsScrollAnchor(
                    lines,
                    currentPositionState + (currentSong?.song?.lyricsOffset ?: 0),
                )
                if (target != -1) {
                    val listIdx = resolveLyricsListAnchorIndex(mergedLyricsList, target)
                    userManualOffset += (positions[listIdx] ?: 0f) + autoRestOffset - restOffsetForAnchor(listIdx)
                    deferredCurrentLineIndex = target
                    scrollTargetIndex = target
                    lastMainMaxSeen = target
                    previousScrollActiveIndices = emptySet()
                }
                isDirectScroll = true
                directScrollRequest++
                isAutoScrollEnabled = true
            },
        )

        LaunchedEffect(Unit) {
            LyricsResyncHelper.resyncTrigger.collect {
                if (latestShowLyrics) {
                    latestResyncLyrics()
                }
            }
        }

        LyricsTranslationHeader(
            status = translationStatus,
            modifier = Modifier.zIndex(1f).padding(top = 56.dp)
        )

        if (lyrics == LYRICS_NOT_FOUND) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.lyrics_not_found), fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.alpha(0.5f))
            }
        } else if (lyrics == null && (translationStatus is LyricsTranslationHelper.TranslationStatus.Idle || translationStatus is LyricsTranslationHelper.TranslationStatus.Error)) {
             Column(modifier = Modifier.padding(top = 100.dp)) {
                 ShimmerHost { repeat(10) { Box(contentAlignment = when (lyricsTextPosition) {
                     LyricsPosition.LEFT -> Alignment.CenterStart; LyricsPosition.CENTER -> Alignment.Center; else -> Alignment.CenterEnd
                 }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) { TextPlaceholder() } } }
             }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdge(top = LYRICS_FADE_TOP_DP, bottom = LYRICS_FADE_BOTTOM_DP)
                    .clipToBounds()
                    .nestedScroll(remember {
                        object : NestedScrollConnection {
                            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                if (source == NestedScrollSource.UserInput) isAutoScrollEnabled = false
                                if (!isSelectionModeActive) lastPreviewTime = System.currentTimeMillis()
                                return super.onPostScroll(consumed, available, source)
                            }
                            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                isAutoScrollEnabled = false
                                if (!isSelectionModeActive) lastPreviewTime = System.currentTimeMillis()
                                return super.onPostFling(consumed, available)
                            }
                        }
                    })
                    .pointerInput(lyrics, mergedLyricsList) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (isInitialLayout) return@awaitEachGesture
                            flingJob?.cancel()
                            velocityTracker.resetTracking()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                isAutoScrollEnabled = false
                                isDirectScroll = false
                                lastPreviewTime = System.currentTimeMillis()
                                userManualOffset = (userManualOffset + overSlop).coerceIn(latestScrollBounds.clampMin, latestScrollBounds.clampMax)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            } ?: return@awaitEachGesture
                            val released = verticalDrag(drag.id) { change ->
                                userManualOffset = (userManualOffset + change.positionChange().y).coerceIn(latestScrollBounds.clampMin, latestScrollBounds.clampMax)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                            if (released) {
                                val velocity = velocityTracker.calculateVelocity().y
                                flingJob = scope.launch {
                                    AnimationState(initialValue = userManualOffset, initialVelocity = velocity).animateDecay(decayAnimSpec) {
                                        val clamped = value.coerceIn(latestScrollBounds.clampMin, latestScrollBounds.clampMax)
                                        userManualOffset = clamped
                                        if (value != clamped) cancelAnimation()
                                    }
                                }
                            }
                        }
                    }
            ) {
                val lyricsOffsetVal = (currentSong?.song?.lyricsOffset ?: 0).toLong()
                val currentEffectivePosition = currentPositionState + lyricsOffsetVal
                
                if (isLyricsProviderShown) {
                    val targetProviderBase = lyricsItemScreenOffset(anchorY, positions[0] ?: 0f, autoRestOffset) - with(density) { 32.dp.toPx() }
                    val animatedProviderBase by animateFloatAsState(
                        targetValue = targetProviderBase,
                        animationSpec = if (isInitialLayout || !isAutoScrollEnabled || isDirectScroll) snap()
                        else {
                            tween(750, 0, FastOutSlowInEasing)
                        },
                        label = "lyricsProviderOffset"
                    )
                    Text(
                        text = stringResource(R.string.lyrics_from_provider, lyricsEntity.provider),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().offset { IntOffset(0, (animatedProviderBase + userManualOffset).roundToInt()) }.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                mergedLyricsList.forEachIndexed { listIndex, listItem ->
                    key(listItem) {
                        val distance = abs(listIndex - activeListIndex)
                        val targetOffset = lyricsItemScreenOffset(
                            anchorY, positions.getOrDefault(listIndex, (listIndex - activeListIndex) * lineHeightPx), autoRestOffset,
                        )
                        val animatedOffset by animateFloatAsState(
                            targetValue = targetOffset,
                            animationSpec = if (isInitialLayout || !isAutoScrollEnabled || isDirectScroll) snap()
                                            else {
                                                tween(750, (distance * LYRICS_STAGGER_DELAY_PER_DISTANCE).coerceAtMost(LYRICS_STAGGER_DELAY_MAX_MS), FastOutSlowInEasing)
                                            },
                            label = "lyricStaggeredOffset_$listIndex"
                        )

                        Box(
                            modifier = Modifier.fillMaxWidth().layout { m, c -> 
                                val p = m.measure(c.copy(maxHeight = Constraints.Infinity))
                                layout(p.width, 0) { p.place(0, 0) }
                            }.offset { IntOffset(0, (animatedOffset + userManualOffset).roundToInt()) }
                        ) {
                            when (listItem) {
                                is LyricsListItem.Indicator -> {
                                    val visible =
                                        isAutoScrollEnabled &&
                                            currentPositionState >= listItem.gapStartMs &&
                                            currentPositionState <= listItem.gapEndMs - 650L
                                    IntervalIndicator(listItem.gapStartMs, listItem.gapEndMs - 650L, currentPositionState, visible, expressiveAccent, 
                                        Modifier.fillMaxWidth().onSizeChanged { itemHeights[listIndex] = it.height }.padding(horizontal = 24.dp).wrapContentWidth(Alignment.CenterHorizontally))
                                }
                                is LyricsListItem.Line -> {
                                    val index = listItem.index
                                    val item = listItem.entry
                                    val isActiveLine = activeLineIndices.contains(index)
                                    val pairedMainLineIndex = if (item.isBackground) (index - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: -1 else -1
                                    
                                    val isInGapWithMain = if (item.isBackground && pairedMainLineIndex != -1) {
                                        val pairedMainLine = lines[pairedMainLineIndex]
                                        currentEffectivePosition >= pairedMainLine.time && currentEffectivePosition <= item.time
                                    } else false
                                    
                                    val bgVisible = item.isBackground && (activeLineIndices.contains(pairedMainLineIndex) || activeLineIndices.contains(index) || isInGapWithMain)
                                    
                                    LyricsLine(
                                        index = index, item = item, isSynced = isSynced,
                                        isActiveLine = isActiveLine,
                                        bgVisible = bgVisible, isSelected = selectedIndices.contains(index),
                                        isSelectionModeActive = isSelectionModeActive, currentPositionState = currentPositionState,
                                        lyricsOffset = (currentSong?.song?.lyricsOffset ?: 0).toLong(),
                                        playerConnection = playerConnection, lyricsTextSize = 36f, lyricsLineSpacing = 1.3f,
                                        expressiveAccent = expressiveAccent, lyricsTextPosition = lyricsTextPosition,
                                        respectAgentPositioning = respectAgentPositioning, isAutoScrollEnabled = isAutoScrollEnabled,
                                        displayedCurrentLineIndex = deferredCurrentLineIndex, romanizeAsMain = romanizeAsMain,
                                        enabledLanguages = enabledLanguages, romanizeLyrics = currentSong?.romanizeLyrics == true,
                                        onSizeChanged = { itemHeights[listIndex] = it },
                                        onClick = {
                                            if (isSelectionModeActive) {
                                                if (selectedIndices.contains(index)) {
                                                    selectedIndices.remove(index)
                                                    if (selectedIndices.isEmpty()) isSelectionModeActive = false
                                                } else if (selectedIndices.size < maxSelectionLimit) selectedIndices.add(index)
                                                else showMaxSelectionToast = true
                                            } else if (changeLyrics && !isGuest) {
                                                flingJob?.cancel()
                                                // Rebase the clicked row in place; only the shared offset animates to its new anchor.
                                                userManualOffset += animatedOffset - anchorY - restOffsetForAnchor(listIndex)
                                                scrollTargetIndex = index
                                                deferredCurrentLineIndex = index
                                                lastMainMaxSeen = index
                                                previousScrollActiveIndices = emptySet()
                                                pendingSeek = null
                                                isDirectScroll = true
                                                directScrollRequest++
                                                if (item.time < playerConnection.player.duration + 30000L) {
                                                    val targetPosition = (item.time - (currentSong?.song?.lyricsOffset ?: 0)).coerceAtLeast(0)
                                                    pendingSeek = LyricsSeekTarget(index, targetPosition, SystemClock.uptimeMillis())
                                                    playerConnection.seekTo(targetPosition)
                                                }
                                                isAutoScrollEnabled = true
                                                lastPreviewTime = 0L
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionModeActive) {
                                                isSelectionModeActive = true
                                                selectedIndices.add(index)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        LyricsActionOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            isAutoScrollEnabled = isAutoScrollEnabled, isSynced = isSynced,
            isSelectionModeActive = isSelectionModeActive, anySelected = selectedIndices.isNotEmpty(),
            onSyncClick = latestResyncLyrics,
            onCancelSelection = { isSelectionModeActive = false; selectedIndices.clear() },
            onShareSelection = {
                val text = selectedIndices.sorted().mapNotNull { lines.getOrNull(it)?.text }.joinToString("\n")
                if (text.isNotBlank()) {
                    shareDialogData = Triple(text, mediaMetadata?.title ?: "", mediaMetadata?.artists?.joinToString { it.name } ?: "")
                    showShareDialog = true
                }
                isSelectionModeActive = false; selectedIndices.clear()
            }
        )
    }

    if (showProgressDialog) {
        BasicAlertDialog(onDismissRequest = {}) {
            Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.padding(32.dp)) { Text(stringResource(R.string.generating_image) + "\n" + stringResource(R.string.please_wait)) }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (txt, title, arts) = shareDialogData!!
        LyricsShareDialog(
            txt = txt, title = title, arts = arts, songId = mediaMetadata?.id ?: "",
            onDismiss = { showShareDialog = false },
            onShareAsImage = {
                showShareDialog = false
                showColorPickerDialog = true
            }
        )
    }

    if (showColorPickerDialog && shareDialogData != null) {
        val (txt, title, arts) = shareDialogData!!
        LyricsColorPickerDialog(
            txt = txt, title = title, arts = arts, thumbnailUrl = mediaMetadata?.thumbnailUrl,
            lyricsTextPosition = lyricsTextPosition,
            onDismiss = { showColorPickerDialog = false },
            onShare = { bgColor, textColor, secTextColor, style ->
                showColorPickerDialog = false
                showProgressDialog = true
                scope.launch {
                    try {
                        val image = ComposeToImage.createLyricsImage(
                            context, mediaMetadata?.thumbnailUrl, title, arts, txt,
                            (configuration.screenWidthDp * density.density).toInt(),
                            (configuration.screenHeightDp * density.density).toInt(),
                            bgColor.toArgb(),
                            when(style) {
                                LyricsBackgroundStyle.SOLID -> LyricsBackgroundStyle.SOLID
                                LyricsBackgroundStyle.BLUR -> LyricsBackgroundStyle.BLUR
                                LyricsBackgroundStyle.GRADIENT -> LyricsBackgroundStyle.GRADIENT
                            },
                            textColor.toArgb(), secTextColor.toArgb(),
                            when (lyricsTextPosition) {
                                LyricsPosition.LEFT -> Layout.Alignment.ALIGN_NORMAL
                                LyricsPosition.CENTER -> Layout.Alignment.ALIGN_CENTER
                                else -> Layout.Alignment.ALIGN_OPPOSITE
                            }
                        )
                        val uri = ComposeToImage.saveBitmapAsFile(context, image, "lyrics_${System.currentTimeMillis()}")
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, context.getString(R.string.share_lyrics)))
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.failed_to_create_image, e.message), Toast.LENGTH_SHORT).show()
                    } finally {
                        showProgressDialog = false
                    }
                }
            }
        )
    }
}

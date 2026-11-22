/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.DEFAULT_LYRICS_LINE_SPACING
import com.metrolist.music.constants.DEFAULT_LYRICS_TEXT_SIZE
import com.metrolist.music.constants.HideStatusBarOnFullscreenKey
import com.metrolist.music.constants.LYRICS_LINE_SPACING_RANGE
import com.metrolist.music.constants.LYRICS_TEXT_SIZE_RANGE
import com.metrolist.music.constants.LyricsAnimationStyle
import com.metrolist.music.constants.LyricsAnimationStyleKey
import com.metrolist.music.constants.LyricsClickKey
import com.metrolist.music.constants.LyricsLineSpacingKey
import com.metrolist.music.constants.LyricsProgressiveBlurKey
import com.metrolist.music.constants.LyricsScrollKey
import com.metrolist.music.constants.LyricsTextPositionKey
import com.metrolist.music.constants.LyricsTextSizeKey
import com.metrolist.music.constants.RespectAgentPositioningKey
import com.metrolist.music.constants.ShowIntervalIndicatorKey
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LyricsAttributionFooter
import com.metrolist.music.ui.component.LyricsLine
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.lyrics.computeProgressiveLyricsBlurDp
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.fadingEdge
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val PREVIEW_DURATION_MS = 10_800L

private fun buildPreviewLyricsSample(): List<LyricsEntry> =
    listOf(
        LyricsEntry(
            time = 0L,
            text = "Turn on the lights tonight",
            words = listOf(
                WordTimestamp("Turn", 0.0, 0.45),
                WordTimestamp("on", 0.45, 0.8),
                WordTimestamp("the", 0.8, 1.1),
                WordTimestamp("lights", 1.1, 1.75),
                WordTimestamp("tonight", 1.75, 2.5, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 2_600L,
            text = "Let the music shine",
            words = listOf(
                WordTimestamp("Let", 2.6, 2.95),
                WordTimestamp("the", 2.95, 3.3),
                WordTimestamp("music", 3.3, 4.05),
                WordTimestamp("shine", 4.05, 5.65, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 5_800L,
            text = "Feel every single beat",
            words = listOf(
                WordTimestamp("Feel", 5.8, 6.3),
                WordTimestamp("every", 6.3, 6.95),
                WordTimestamp("single", 6.95, 7.6),
                WordTimestamp("beat", 7.6, 8.3, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 8_400L,
            text = "Until the morning light",
            words = listOf(
                WordTimestamp("Until", 8.4, 8.9),
                WordTimestamp("the", 8.9, 9.25),
                WordTimestamp("morning", 9.25, 9.9),
                WordTimestamp("light", 9.9, 10.65, hasTrailingSpace = false),
            ),
        ),
    )

@Composable
internal fun lyricsAnimationStyleLabel(style: LyricsAnimationStyle): String =
    when (style) {
        LyricsAnimationStyle.METROVERSE -> stringResource(R.string.lyrics_animation_metroverse)
        LyricsAnimationStyle.APPLE -> stringResource(R.string.lyrics_animation_apple_music)
        LyricsAnimationStyle.NONE -> stringResource(R.string.lyrics_animation_none)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsAppearanceSettings(navController: NavController) {
    val (rawAnimationStyle, setAnimationStyle) = rememberPreference(
        LyricsAnimationStyleKey,
        LyricsAnimationStyle.METROVERSE.name,
    )
    val animationStyle = remember(rawAnimationStyle) {
        LyricsAnimationStyle.fromStoredValue(rawAnimationStyle)
    }
    val (lyricsTextSize, onLyricsTextSizeChange) = rememberPreference(
        LyricsTextSizeKey,
        DEFAULT_LYRICS_TEXT_SIZE,
    )
    val clampedTextSize = lyricsTextSize.coerceIn(LYRICS_TEXT_SIZE_RANGE)
    val (lyricsLineSpacing, onLyricsLineSpacingChange) = rememberPreference(
        LyricsLineSpacingKey,
        DEFAULT_LYRICS_LINE_SPACING,
    )
    val clampedLineSpacing = lyricsLineSpacing.coerceIn(LYRICS_LINE_SPACING_RANGE)
    val (lyricsProgressiveBlur, onLyricsProgressiveBlurChange) = rememberPreference(
        LyricsProgressiveBlurKey,
        false,
    )
    val (lyricsPosition, onLyricsPositionChange) = rememberEnumPreference(
        LyricsTextPositionKey,
        LyricsPosition.CENTER,
    )
    val (respectAgentPositioning, onRespectAgentPositioningChange) = rememberPreference(
        RespectAgentPositioningKey,
        true,
    )
    val (lyricsClick, onLyricsClickChange) = rememberPreference(LyricsClickKey, true)
    val (lyricsScroll, onLyricsScrollChange) = rememberPreference(LyricsScrollKey, true)
    val (showIntervalIndicator, onShowIntervalIndicatorChange) = rememberPreference(
        ShowIntervalIndicatorKey,
        true,
    )
    val (hideStatusBarOnFullscreen, onHideStatusBarOnFullscreenChange) = rememberPreference(
        HideStatusBarOnFullscreenKey,
        false,
    )

    var showAnimationStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showPositionDialog by rememberSaveable { mutableStateOf(false) }

    if (showAnimationStyleDialog) {
        EnumDialog(
            onDismiss = { showAnimationStyleDialog = false },
            onSelect = {
                setAnimationStyle(it.name)
                showAnimationStyleDialog = false
            },
            title = stringResource(R.string.lyrics_animation_style_title),
            current = animationStyle,
            values = LyricsAnimationStyle.entries,
            valueText = {
                when (it) {
                    LyricsAnimationStyle.METROVERSE -> stringResource(R.string.lyrics_animation_metroverse)
                    LyricsAnimationStyle.APPLE -> stringResource(R.string.lyrics_animation_apple_music)
                    LyricsAnimationStyle.NONE -> stringResource(R.string.lyrics_animation_none)
                }
            },
        )
    }

    if (showPositionDialog) {
        EnumDialog(
            onDismiss = { showPositionDialog = false },
            onSelect = {
                onLyricsPositionChange(it)
                showPositionDialog = false
            },
            title = stringResource(R.string.lyrics_text_position),
            current = lyricsPosition,
            values = LyricsPosition.entries,
            valueText = {
                when (it) {
                    LyricsPosition.LEFT -> stringResource(R.string.left)
                    LyricsPosition.CENTER -> stringResource(R.string.center)
                    LyricsPosition.RIGHT -> stringResource(R.string.right)
                }
            },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        LyricsAppearancePreviewCard(
            animationStyle = animationStyle,
            lyricsTextSize = clampedTextSize,
            lyricsLineSpacing = clampedLineSpacing,
            lyricsPosition = lyricsPosition,
            progressiveBlurEnabled = lyricsProgressiveBlur,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics_display_section),
            items =
                buildList {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.lyrics),
                            title = { Text(stringResource(R.string.lyrics_animation_style_title)) },
                            description = { Text(lyricsAnimationStyleLabel(animationStyle)) },
                            onClick = { showAnimationStyleDialog = true },
                        ),
                    )
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.tune),
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.lyrics_text_size))
                                    Text(
                                        text = "${clampedTextSize.roundToInt()} sp",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            description = {
                                Slider(
                                    value = clampedTextSize,
                                    onValueChange = { onLyricsTextSizeChange(it.roundToInt().toFloat()) },
                                    valueRange = LYRICS_TEXT_SIZE_RANGE,
                                    steps = 33,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            trailingContent = {
                                MaterialIconButton(
                                    onClick = { onLyricsTextSizeChange(DEFAULT_LYRICS_TEXT_SIZE) },
                                    enabled = clampedTextSize.roundToInt() != DEFAULT_LYRICS_TEXT_SIZE.roundToInt(),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.refresh),
                                        contentDescription = stringResource(R.string.reset),
                                    )
                                }
                            },
                        ),
                    )
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.tune),
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.lyrics_line_spacing))
                                    Text(
                                        text = String.format(Locale.US, "%.1fx", clampedLineSpacing),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            description = {
                                Slider(
                                    value = clampedLineSpacing,
                                    onValueChange = {
                                        val snapped = (it * 10f).roundToInt() / 10f
                                        onLyricsLineSpacingChange(snapped)
                                    },
                                    valueRange = LYRICS_LINE_SPACING_RANGE,
                                    steps = 14,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            trailingContent = {
                                MaterialIconButton(
                                    onClick = { onLyricsLineSpacingChange(DEFAULT_LYRICS_LINE_SPACING) },
                                    enabled = abs(clampedLineSpacing - DEFAULT_LYRICS_LINE_SPACING) > 0.05f,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.refresh),
                                        contentDescription = stringResource(R.string.reset),
                                    )
                                }
                            },
                        ),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.gradient),
                                title = { Text(stringResource(R.string.lyrics_progressive_blur)) },
                                description = { Text(stringResource(R.string.lyrics_progressive_blur_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = lyricsProgressiveBlur,
                                        onCheckedChange = onLyricsProgressiveBlurChange,
                                        thumbContent = {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (lyricsProgressiveBlur) R.drawable.check else R.drawable.close,
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        },
                                    )
                                },
                                onClick = { onLyricsProgressiveBlurChange(!lyricsProgressiveBlur) },
                            ),
                        )
                    }
                },
        )

        Spacer(modifier = Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics_alignment_section),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.lyrics_text_position)) },
                        description = {
                            Text(
                                when (lyricsPosition) {
                                    LyricsPosition.LEFT -> stringResource(R.string.left)
                                    LyricsPosition.CENTER -> stringResource(R.string.center)
                                    LyricsPosition.RIGHT -> stringResource(R.string.right)
                                },
                            )
                        },
                        onClick = { showPositionDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.respect_agent_positioning)) },
                        description = { Text(stringResource(R.string.respect_agent_positioning_desc)) },
                        trailingContent = {
                            Switch(
                                checked = respectAgentPositioning,
                                onCheckedChange = onRespectAgentPositioningChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (respectAgentPositioning) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onRespectAgentPositioningChange(!respectAgentPositioning) },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics_behavior_section),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.lyrics_click_change)) },
                        trailingContent = {
                            Switch(
                                checked = lyricsClick,
                                onCheckedChange = onLyricsClickChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (lyricsClick) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onLyricsClickChange(!lyricsClick) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.lyrics_auto_scroll)) },
                        trailingContent = {
                            Switch(
                                checked = lyricsScroll,
                                onCheckedChange = onLyricsScrollChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (lyricsScroll) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onLyricsScrollChange(!lyricsScroll) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.show_interval_indicator)) },
                        description = { Text(stringResource(R.string.show_interval_indicator_desc)) },
                        trailingContent = {
                            Switch(
                                checked = showIntervalIndicator,
                                onCheckedChange = onShowIntervalIndicatorChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (showIntervalIndicator) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onShowIntervalIndicatorChange(!showIntervalIndicator) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.hide_status_bar_fullscreen)) },
                        description = { Text(stringResource(R.string.hide_status_bar_fullscreen_desc)) },
                        trailingContent = {
                            Switch(
                                checked = hideStatusBarOnFullscreen,
                                onCheckedChange = onHideStatusBarOnFullscreenChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (hideStatusBarOnFullscreen) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onHideStatusBarOnFullscreenChange(!hideStatusBarOnFullscreen) },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.lyrics)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
    )
}

@Composable
private fun LyricsAppearancePreviewCard(
    animationStyle: LyricsAnimationStyle,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    lyricsPosition: LyricsPosition,
    progressiveBlurEnabled: Boolean,
) {
    val sampleLines = remember { buildPreviewLyricsSample() }
    val previewMeasurer = rememberTextMeasurer(cacheSize = 64)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val positionState = remember { mutableLongStateOf(0L) }
    val stablePositionProvider = remember { { positionState.longValue } }
    var activeIndex by remember { mutableIntStateOf(0) }
    var baseOffsetMs by remember { mutableLongStateOf(0L) }
    var clockStartWallMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lifecycleState, animationStyle) {
        if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        clockStartWallMs = System.currentTimeMillis()
        while (isActive) {
            withFrameNanos { }
            val elapsed = (baseOffsetMs + (System.currentTimeMillis() - clockStartWallMs)) % PREVIEW_DURATION_MS
            positionState.longValue = elapsed
            val nextActive = sampleLines.indexOfLast { it.time <= elapsed }.coerceAtLeast(0)
            if (nextActive != activeIndex) {
                activeIndex = nextActive
            }
        }
    }

    val previewTextSize = remember(lyricsTextSize) { (lyricsTextSize * 0.68f).coerceIn(12f, 32f) }
    val previewHeights = remember { mutableStateMapOf<Int, Int>() }
    val density = LocalDensity.current
    val scrollShiftPx = remember(activeIndex, previewHeights.toMap()) {
        val above = (0 until activeIndex).sumOf { previewHeights[it] ?: 0 }.toFloat()
        val halfViewport = with(density) { 82.dp.toPx() }
        val activeH = (previewHeights[activeIndex] ?: 0) / 2f
        (halfViewport - above - activeH).coerceAtMost(0f)
    }
    val animatedScrollY by animateFloatAsState(
        targetValue = scrollShiftPx,
        animationSpec = tween(durationMillis = 450),
        label = "previewLyricsScroll",
    )

    val containerBg = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
    val accentColor = Color.White

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerBg),
        ) {
            Text(
                text = stringResource(R.string.lyrics_preview),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = accentColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .clipToBounds()
                    .fadingEdge(top = 24.dp, bottom = 32.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = animatedScrollY },
                ) {
                    sampleLines.forEachIndexed { idx, entry ->
                        val distance = abs(idx - activeIndex)
                        val blurDp = computeProgressiveLyricsBlurDp(
                            distance = distance,
                            enabled = progressiveBlurEnabled,
                            isSynced = true,
                            isAutoScrollEnabled = true,
                            isManualScrolling = false,
                            isSelectionMode = false,
                            isAttributionItem = false,
                        )
                        val endMs = entry.words?.lastOrNull()?.let { (it.endTime * 1000).toLong() }
                        LyricsLine(
                            index = idx,
                            item = entry,
                            isSynced = true,
                            isActiveLine = idx == activeIndex,
                            bgVisible = false,
                            isSelected = false,
                            isSelectionModeActive = false,
                            positionProvider = stablePositionProvider,
                            adjustedEndTimeMs = endMs,
                            isPlaying = lifecycleState == Lifecycle.State.RESUMED,
                            playbackSpeed = 1f,
                            lyricsTextSize = previewTextSize,
                            lyricsLineSpacing = lyricsLineSpacing,
                            animationStyle = animationStyle,
                            progressiveBlurDp = blurDp,
                            distance = distance,
                            expressiveAccent = accentColor,
                            textMeasurer = previewMeasurer,
                            lyricsTextPosition = lyricsPosition,
                            respectAgentPositioning = false,
                            isAutoScrollEnabled = true,
                            displayedCurrentLineIndex = activeIndex,
                            romanizeAsMain = false,
                            enabledLanguages = emptyList(),
                            romanizeLyrics = false,
                            onSizeChanged = { previewHeights[idx] = it },
                            onClick = {
                                baseOffsetMs = entry.time
                                clockStartWallMs = System.currentTimeMillis()
                                positionState.longValue = entry.time
                                activeIndex = idx
                            },
                            onLongClick = {},
                        )
                    }

                    // Bottom attribution stays sharp even with progressive blur enabled.
                    LyricsAttributionFooter(
                        provider = "MetroVerse",
                        songwriters = listOf("MetroVerse Studio"),
                        textColor = accentColor,
                        textAlign = when (lyricsPosition) {
                            LyricsPosition.LEFT -> TextAlign.Left
                            LyricsPosition.CENTER -> TextAlign.Center
                            LyricsPosition.RIGHT -> TextAlign.Right
                        },
                    )
                }
            }
        }
    }
}

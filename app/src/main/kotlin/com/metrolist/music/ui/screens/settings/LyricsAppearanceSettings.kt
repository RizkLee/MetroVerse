/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavController
import com.metrolist.innertube.models.MUSIC_VIDEO_TYPE_PODCAST_EPISODE
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
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
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.lyrics.lyricsTextLooksSynced
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LyricsAttributionFooter
import com.metrolist.music.ui.component.LyricsLine
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.computeAutoRestOffset
import com.metrolist.music.ui.component.computeLyricsItemPositions
import com.metrolist.music.ui.component.computeLyricsScrollLookAheadMs
import com.metrolist.music.ui.component.lyrics.computeProgressiveLyricsBlurDp
import com.metrolist.music.ui.component.lyricsItemScreenOffset
import com.metrolist.music.ui.component.lyricsScrollMotionSpec
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.fadingEdge
import com.metrolist.music.utils.isOpenRssEpisode
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SAMPLE_PREVIEW_DURATION_MS = 19_200L
private const val SAMPLE_PREVIEW_INITIAL_OFFSET_MS = 2_550L
private const val PLAIN_LYRICS_LINE_STEP_MS = 2_600L

internal data class LyricsPreviewSource(
    val lines: List<LyricsEntry>,
    val isLivePlayback: Boolean,
    val isSynced: Boolean,
    val provider: String?,
)

/**
 * Resolves the scroll target line for the lyrics preview using the current highlight line's
 * look-ahead and maintaining forward-only progression during normal playback so ultra-short
 * upcoming lines cannot oscillate the preview back and forth.
 */
internal fun resolvePreviewScrollAnchorIndex(
    lines: List<LyricsEntry>,
    currentHighlightIndex: Int,
    previousScrollAnchorIndex: Int,
    effectivePositionMs: Long,
    previousPositionMs: Long,
    hasWordTimings: Boolean,
    maxLookAheadPositionMs: Long? = null,
    animationStyle: LyricsAnimationStyle = LyricsAnimationStyle.APPLE,
): Int {
    if (lines.isEmpty()) return 0
    val lookAheadMs =
        computeLyricsScrollLookAheadMs(
            lines = lines,
            currentAnchorIndex = currentHighlightIndex,
            hasWordTimings = hasWordTimings,
            animationStyle = animationStyle,
        )
    val rawScrollPos = effectivePositionMs + lookAheadMs
    val scrollPos = maxLookAheadPositionMs?.let { rawScrollPos.coerceAtMost(it) } ?: rawScrollPos
    val activeSet = LyricsUtils.findActiveLineIndices(lines, scrollPos)
    val candidate =
        activeSet.lastOrNull { lines.getOrNull(it)?.isBackground == false }
            ?: activeSet.lastOrNull()
            ?: lines.indexOfLast { it.time <= scrollPos }.coerceAtLeast(0)
    return if (effectivePositionMs >= previousPositionMs && previousScrollAnchorIndex in lines.indices) {
        maxOf(previousScrollAnchorIndex, candidate)
    } else {
        candidate
    }
}

internal fun buildPreviewLyricsSample(): List<LyricsEntry> =
    listOf(
        LyricsEntry(
            time = 0L,
            text = "Turn up the light",
            words = listOf(
                WordTimestamp("Turn", 0.0, 0.45),
                WordTimestamp("up", 0.45, 0.8),
                WordTimestamp("the", 0.8, 1.15),
                WordTimestamp("light", 1.15, 2.05, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 2_200L,
            text = "Feel the night glow",
            words = listOf(
                WordTimestamp("Feel", 2.2, 2.65),
                WordTimestamp("the", 2.65, 3.0),
                WordTimestamp("night", 3.0, 3.55),
                WordTimestamp("glow", 3.55, 4.35, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 4_500L,
            text = "Every beat plays on",
            words = listOf(
                WordTimestamp("Every", 4.5, 5.0),
                WordTimestamp("beat", 5.0, 5.45),
                WordTimestamp("plays", 5.45, 6.05),
                WordTimestamp("on", 6.05, 6.65, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 6_800L,
            text = "Singing by the bay",
            words = listOf(
                WordTimestamp("Singing", 6.8, 7.35),
                WordTimestamp("by", 7.35, 7.7),
                WordTimestamp("the", 7.7, 8.05),
                WordTimestamp("bay", 8.05, 8.95, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 9_100L,
            text = "Dancing on waves",
            words = listOf(
                WordTimestamp("Dancing", 9.1, 9.8),
                WordTimestamp("on", 9.8, 10.25),
                WordTimestamp("waves", 10.25, 11.25, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 11_400L,
            text = "Softly drift away",
            words = listOf(
                WordTimestamp("Softly", 11.4, 12.1),
                WordTimestamp("drift", 12.1, 12.75),
                WordTimestamp("away", 12.75, 13.55, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 13_700L,
            text = "Wait for sunrise",
            words = listOf(
                WordTimestamp("Wait", 13.7, 14.3),
                WordTimestamp("for", 14.3, 14.95),
                WordTimestamp("sunrise", 14.95, 15.85, hasTrailingSpace = false),
            ),
        ),
        LyricsEntry(
            time = 16_000L,
            text = "Joyful open sky",
            words = listOf(
                WordTimestamp("Joyful", 16.0, 16.65),
                WordTimestamp("open", 16.65, 17.25),
                WordTimestamp("sky", 17.25, 18.0, hasTrailingSpace = false),
            ),
        ),
    )

internal fun resolveLyricsPreviewSource(
    isEffectivelyPlaying: Boolean,
    isPodcastEpisode: Boolean,
    rawLyrics: String?,
    provider: String?,
): LyricsPreviewSource {
    val cleanRaw = rawLyrics?.trim()
    if (isEffectivelyPlaying && !isPodcastEpisode && !cleanRaw.isNullOrBlank() && cleanRaw != LYRICS_NOT_FOUND) {
        if (lyricsTextLooksSynced(cleanRaw)) {
            val parsed = LyricsUtils.parseLyrics(cleanRaw).filter { it.text.isNotBlank() }
            if (parsed.isNotEmpty()) {
                return LyricsPreviewSource(
                    lines = parsed,
                    isLivePlayback = true,
                    isSynced = true,
                    provider = provider,
                )
            }
        }
        val plainLines =
            cleanRaw
                .lines()
                .map(String::trim)
                .filter(String::isNotBlank)
                .mapIndexed { index, text ->
                    LyricsEntry(time = index * PLAIN_LYRICS_LINE_STEP_MS, text = text)
                }
        if (plainLines.isNotEmpty()) {
            return LyricsPreviewSource(
                lines = plainLines,
                isLivePlayback = false,
                isSynced = false,
                provider = provider,
            )
        }
    }
    return LyricsPreviewSource(
        lines = buildPreviewLyricsSample(),
        isLivePlayback = false,
        isSynced = true,
        provider = "MetroVerse",
    )
}

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
        LyricsAnimationStyle.APPLE.name,
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
    val playerConnection = LocalPlayerConnection.current
    val isEffectivelyPlaying by (
        playerConnection?.isEffectivelyPlaying?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf(false) }
    )
    val mediaMetadata by (
        playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf(null) }
    )
    val currentSong by (
        playerConnection?.currentSong?.collectAsStateWithLifecycle(initialValue = null)
            ?: remember { mutableStateOf(null) }
    )
    val currentLyrics by (
        playerConnection?.currentLyrics?.collectAsStateWithLifecycle(initialValue = null)
            ?: remember { mutableStateOf(null) }
    )

    val isPodcastEpisode = remember(mediaMetadata) {
        val m = mediaMetadata
        m != null && (m.isEpisode || m.isOpenRssEpisode() || m.musicVideoType == MUSIC_VIDEO_TYPE_PODCAST_EPISODE)
    }
    val previewSource = remember(
        isEffectivelyPlaying,
        isPodcastEpisode,
        currentLyrics?.lyrics,
        currentLyrics?.provider,
    ) {
        resolveLyricsPreviewSource(
            isEffectivelyPlaying = isEffectivelyPlaying,
            isPodcastEpisode = isPodcastEpisode,
            rawLyrics = currentLyrics?.lyrics,
            provider = currentLyrics?.provider,
        )
    }

    val previewMeasurer = rememberTextMeasurer(cacheSize = 64)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val positionState = remember(previewSource) {
        mutableLongStateOf(if (previewSource.isLivePlayback) 0L else SAMPLE_PREVIEW_INITIAL_OFFSET_MS)
    }
    val stablePositionProvider = remember(positionState) { { positionState.longValue } }
    val loopDurationMs = remember(previewSource) {
        if (previewSource.isLivePlayback) {
            1L
        } else if (previewSource.isSynced) {
            maxOf(
                SAMPLE_PREVIEW_DURATION_MS,
                (previewSource.lines.lastOrNull()?.words?.lastOrNull()?.endTime?.times(1000L)?.toLong() ?: 0L) + 1_200L,
            )
        } else {
            maxOf(1L, previewSource.lines.size * PLAIN_LYRICS_LINE_STEP_MS)
        }
    }
    val hasWordTimings = remember(previewSource) {
        previewSource.lines.any { !it.words.isNullOrEmpty() }
    }
    val initialActiveIndex = remember(previewSource) {
        if (previewSource.isLivePlayback) {
            0
        } else {
            previewSource.lines.indexOfLast { it.time <= SAMPLE_PREVIEW_INITIAL_OFFSET_MS }.coerceAtLeast(0)
        }
    }
    val initialScrollAnchorIndex = remember(previewSource, initialActiveIndex, hasWordTimings, loopDurationMs, animationStyle) {
        if (previewSource.isLivePlayback) {
            0
        } else {
            resolvePreviewScrollAnchorIndex(
                lines = previewSource.lines,
                currentHighlightIndex = initialActiveIndex,
                previousScrollAnchorIndex = initialActiveIndex,
                effectivePositionMs = SAMPLE_PREVIEW_INITIAL_OFFSET_MS,
                previousPositionMs = SAMPLE_PREVIEW_INITIAL_OFFSET_MS,
                hasWordTimings = hasWordTimings,
                maxLookAheadPositionMs = loopDurationMs,
                animationStyle = animationStyle,
            )
        }
    }
    var activeHighlightIndex by remember(previewSource) { mutableIntStateOf(initialActiveIndex) }
    var scrollAnchorIndex by remember(previewSource) { mutableIntStateOf(initialScrollAnchorIndex) }
    var baseOffsetMs by remember(previewSource) {
        mutableLongStateOf(if (previewSource.isLivePlayback) 0L else SAMPLE_PREVIEW_INITIAL_OFFSET_MS)
    }
    var clockStartWallMs by remember(previewSource) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lifecycleState, previewSource, animationStyle, currentSong?.song?.lyricsOffset) {
        if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        clockStartWallMs = System.currentTimeMillis()
        var lastPlayerPos = playerConnection?.player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        var lastPlayerSampleMs = System.currentTimeMillis()
        var lastLiveEffectivePos = Long.MAX_VALUE
        var lastSampleElapsed = Long.MAX_VALUE
        while (isActive) {
            withFrameNanos { }
            if (previewSource.isLivePlayback && playerConnection != null) {
                val now = System.currentTimeMillis()
                val rawPos = playerConnection.player.currentPosition.coerceAtLeast(0L)
                if (rawPos != lastPlayerPos) {
                    lastPlayerPos = rawPos
                    lastPlayerSampleMs = now
                }
                val speed = playerConnection.player.playbackParameters.speed.coerceAtLeast(0.1f)
                val interpolated =
                    if (playerConnection.player.isPlaying) {
                        lastPlayerPos + ((now - lastPlayerSampleMs).coerceAtMost(32L) * speed).toLong()
                    } else {
                        lastPlayerPos
                    }
                val lyricsOffset = currentSong?.song?.lyricsOffset ?: 0
                val effectivePos = (interpolated + lyricsOffset).coerceAtLeast(0L)
                positionState.longValue = effectivePos
                val activeSet = LyricsUtils.findActiveLineIndices(previewSource.lines, effectivePos)
                val nextHighlight =
                    activeSet.lastOrNull { previewSource.lines.getOrNull(it)?.isBackground == false }
                        ?: activeSet.lastOrNull()
                        ?: previewSource.lines.indexOfLast { it.time <= effectivePos }.coerceAtLeast(0)
                if (nextHighlight != activeHighlightIndex) {
                    activeHighlightIndex = nextHighlight
                }
                val nextScrollAnchor =
                    resolvePreviewScrollAnchorIndex(
                        lines = previewSource.lines,
                        currentHighlightIndex = nextHighlight,
                        previousScrollAnchorIndex = scrollAnchorIndex,
                        effectivePositionMs = effectivePos,
                        previousPositionMs = lastLiveEffectivePos,
                        hasWordTimings = hasWordTimings,
                        animationStyle = animationStyle,
                    )
                lastLiveEffectivePos = effectivePos
                if (nextScrollAnchor != scrollAnchorIndex) {
                    scrollAnchorIndex = nextScrollAnchor
                }
            } else {
                val elapsed = (baseOffsetMs + (System.currentTimeMillis() - clockStartWallMs)) % loopDurationMs
                positionState.longValue = elapsed
                val nextHighlight = previewSource.lines.indexOfLast { it.time <= elapsed }.coerceAtLeast(0)
                if (nextHighlight != activeHighlightIndex) {
                    activeHighlightIndex = nextHighlight
                }
                val nextScrollAnchor =
                    resolvePreviewScrollAnchorIndex(
                        lines = previewSource.lines,
                        currentHighlightIndex = nextHighlight,
                        previousScrollAnchorIndex = scrollAnchorIndex,
                        effectivePositionMs = elapsed,
                        previousPositionMs = lastSampleElapsed,
                        hasWordTimings = hasWordTimings,
                        maxLookAheadPositionMs = loopDurationMs,
                        animationStyle = animationStyle,
                    )
                lastSampleElapsed = elapsed
                if (nextScrollAnchor != scrollAnchorIndex) {
                    scrollAnchorIndex = nextScrollAnchor
                }
            }
        }
    }

    val previewHeightDp = 320.dp
    val density = LocalDensity.current
    val containerBg = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1F2B3A),
                Color(0xFF121821),
            ),
        )
    }
    val accentColor = Color.White
    val footerAlign = when (lyricsPosition) {
        LyricsPosition.LEFT -> TextAlign.Left
        LyricsPosition.CENTER -> TextAlign.Center
        LyricsPosition.RIGHT -> TextAlign.Right
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF121821),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerBg),
        ) {
            Text(
                text = stringResource(R.string.lyrics_preview),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = accentColor.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            key(previewSource, animationStyle) {
                BoxWithConstraints(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeightDp)
                        .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                        .clipToBounds()
                        .fadingEdge(top = 24.dp, bottom = 32.dp)
                        .padding(horizontal = 12.dp),
                ) {
                    val maxHeightPx = constraints.maxHeight.toFloat()
                    val anchorY = maxHeightPx * 0.25f
                    val gapPx = with(density) { 12.dp.toPx() }
                    val fallbackLineHeightPx = with(density) { (lyricsTextSize * lyricsLineSpacing * 1.5f + 24f).dp.toPx() }
                    val topPad = with(density) { 24.dp.toPx() }
                    val bottomPad = with(density) { 28.dp.toPx() }
                    val totalCount = previewSource.lines.size + 1
                    val activeListIndex = scrollAnchorIndex.coerceIn(0, (previewSource.lines.size - 1).coerceAtLeast(0))
                    val scrollMotion = remember(animationStyle) { lyricsScrollMotionSpec(animationStyle) }

                    val itemHeights =
                        remember(previewSource, animationStyle, lyricsTextSize, lyricsLineSpacing, lyricsPosition) {
                            mutableStateMapOf<Int, Int>()
                        }
                    var isInitialLayout by
                        remember(previewSource, animationStyle, lyricsTextSize, lyricsLineSpacing, lyricsPosition) {
                            mutableStateOf(true)
                        }

                    val resolveItemHeight: (Int) -> Float =
                        remember(itemHeights.toMap(), previewSource, fallbackLineHeightPx) {
                            val measuredAverage =
                                if (itemHeights.isEmpty()) {
                                    null
                                } else {
                                    itemHeights.values.average().toFloat().coerceAtLeast(1f)
                                }
                            { index: Int ->
                                itemHeights[index]?.toFloat() ?: measuredAverage ?: fallbackLineHeightPx
                            }
                        }

                    val positions =
                        remember(itemHeights.toMap(), activeListIndex, previewSource, gapPx, fallbackLineHeightPx) {
                            computeLyricsItemPositions(
                                itemHeights = (0 until totalCount).map(resolveItemHeight),
                                gapsBeforeItems = (0 until totalCount).map { idx ->
                                    if (idx < previewSource.lines.size && previewSource.lines[idx].isBackground) 0f else gapPx
                                },
                                anchorIndex = activeListIndex,
                            )
                        }

                    val autoRestOffset =
                        remember(itemHeights.toMap(), positions, activeListIndex, maxHeightPx, anchorY, topPad, bottomPad, totalCount) {
                            computeAutoRestOffset(
                                contentAbove = -(positions[0] ?: 0f),
                                contentBelow = positions[totalCount - 1] ?: 0f,
                                lastItemHeight = resolveItemHeight(totalCount - 1),
                                viewportHeight = maxHeightPx,
                                anchorY = anchorY,
                                topPadding = topPad,
                                bottomPadding = bottomPad,
                            )
                        }

                    LaunchedEffect(previewSource, animationStyle, lyricsTextSize, lyricsLineSpacing, lyricsPosition) {
                        val windowStart = (activeListIndex - 2).coerceAtLeast(0)
                        val windowEnd = (activeListIndex + 2).coerceAtMost(totalCount - 1)
                        if ((windowStart..windowEnd).all { itemHeights.containsKey(it) }) {
                            isInitialLayout = false
                        } else {
                            isInitialLayout = true
                            withTimeoutOrNull(400L) {
                                snapshotFlow {
                                    val h = itemHeights.toMap()
                                    (windowStart..windowEnd).all { h.containsKey(it) }
                                }.first { it }
                            }
                            isInitialLayout = false
                        }
                    }

                    val renderStart = (activeListIndex - 5).coerceAtLeast(0)
                    val renderEnd = (activeListIndex + 6).coerceAtMost(totalCount - 1)

                    for (listIndex in renderStart..renderEnd) {
                        val stableKey =
                            if (listIndex < previewSource.lines.size) {
                                "line_$listIndex"
                            } else {
                                "provider_footer"
                            }
                        key(stableKey) {
                            val distance = abs(listIndex - activeListIndex)
                            val targetOffset = lyricsItemScreenOffset(
                                anchorY = anchorY,
                                relativeOffset = positions.getOrDefault(listIndex, (listIndex - activeListIndex) * fallbackLineHeightPx),
                                restOffset = autoRestOffset,
                            )
                            val animatedOffset by animateFloatAsState(
                                targetValue = targetOffset,
                                animationSpec = if (isInitialLayout) {
                                    snap()
                                } else {
                                    tween(
                                        durationMillis = scrollMotion.durationMs,
                                        delayMillis = (distance * scrollMotion.staggerDelayPerDistanceMs).coerceAtMost(scrollMotion.staggerDelayMaxMs),
                                        easing = FastOutSlowInEasing,
                                    )
                                },
                                label = "previewLyricStaggeredOffset_$listIndex",
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .layout { m, c ->
                                        val p = m.measure(c.copy(maxHeight = Constraints.Infinity))
                                        layout(p.width, 0) { p.place(0, 0) }
                                    }
                                    .offset { IntOffset(0, animatedOffset.roundToInt()) },
                            ) {
                                if (listIndex < previewSource.lines.size) {
                                    val entry = previewSource.lines[listIndex]
                                    val blurDp = computeProgressiveLyricsBlurDp(
                                        distance = distance,
                                        enabled = progressiveBlurEnabled,
                                        isSynced = previewSource.isSynced,
                                        isAutoScrollEnabled = true,
                                        isManualScrolling = false,
                                        isSelectionMode = false,
                                        isAttributionItem = false,
                                    )
                                    val endMs =
                                        entry.words?.lastOrNull()?.let { (it.endTime * 1000).toLong() }
                                            ?: previewSource.lines.getOrNull(listIndex + 1)?.time
                                    LyricsLine(
                                        index = listIndex,
                                        item = entry,
                                        isSynced = previewSource.isSynced,
                                        isActiveLine = !previewSource.isSynced || listIndex == activeHighlightIndex,
                                        bgVisible = previewSource.isSynced && listIndex == activeHighlightIndex,
                                        isSelected = false,
                                        isSelectionModeActive = false,
                                        positionProvider = stablePositionProvider,
                                        adjustedEndTimeMs = endMs,
                                        isPlaying = lifecycleState == Lifecycle.State.RESUMED,
                                        playbackSpeed = if (previewSource.isLivePlayback && playerConnection != null) {
                                            playerConnection.player.playbackParameters.speed.coerceAtLeast(0.1f)
                                        } else {
                                            1f
                                        },
                                        lyricsTextSize = lyricsTextSize,
                                        lyricsLineSpacing = lyricsLineSpacing,
                                        animationStyle = animationStyle,
                                        progressiveBlurDp = blurDp,
                                        distance = distance,
                                        expressiveAccent = accentColor,
                                        textMeasurer = previewMeasurer,
                                        lyricsTextPosition = lyricsPosition,
                                        respectAgentPositioning = false,
                                        isAutoScrollEnabled = true,
                                        displayedCurrentLineIndex = activeListIndex,
                                        romanizeAsMain = false,
                                        enabledLanguages = emptyList(),
                                        romanizeLyrics = false,
                                        onSizeChanged = { itemHeights[listIndex] = it },
                                        onClick = {
                                            if (!previewSource.isLivePlayback) {
                                                baseOffsetMs = entry.time
                                                clockStartWallMs = System.currentTimeMillis()
                                                positionState.longValue = entry.time
                                                activeHighlightIndex = listIndex
                                                scrollAnchorIndex = listIndex
                                            }
                                        },
                                        onLongClick = {},
                                    )
                                } else {
                                    LyricsAttributionFooter(
                                        provider = previewSource.provider ?: "MetroVerse",
                                        songwriters = if (previewSource.isLivePlayback) emptyList() else listOf("MetroVerse Studio"),
                                        textColor = accentColor,
                                        textAlign = footerAlign,
                                        modifier = Modifier.onSizeChanged { itemHeights[listIndex] = it.height },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

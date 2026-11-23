/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AppIconKey
import com.metrolist.music.constants.AppUsageMode
import com.metrolist.music.constants.AppUsageModeKey
import com.metrolist.music.constants.ChipSortTypeKey
import com.metrolist.music.constants.applyAppUsageMode
import com.metrolist.music.constants.resolveAppUsageMode
import com.metrolist.music.constants.CropAlbumArtKey
import com.metrolist.music.constants.DEFAULT_LYRICS_TEXT_SIZE
import com.metrolist.music.constants.DefaultOpenTabKey
import com.metrolist.music.constants.DensityScale
import com.metrolist.music.constants.DensityScaleKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.EnableHighRefreshRateKey
import com.metrolist.music.constants.EnableLandscapeScalingKey
import com.metrolist.music.constants.GridItemSize
import com.metrolist.music.constants.GridItemsSizeKey
import com.metrolist.music.constants.HidePlayerThumbnailKey
import com.metrolist.music.constants.LYRICS_TEXT_SIZE_RANGE
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.constants.LyricsAnimationStyle
import com.metrolist.music.constants.LyricsAnimationStyleKey
import com.metrolist.music.constants.LyricsTextSizeKey
import com.metrolist.music.constants.MiniPlayerBackgroundStyle
import com.metrolist.music.constants.MiniPlayerBackgroundStyleKey
import com.metrolist.music.constants.NavigationBarTabsKey
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PlayerButtonsStyle
import com.metrolist.music.constants.PlayerButtonsStyleKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.constants.ShowCachedPlaylistKey
import com.metrolist.music.constants.ShowDownloadedPlaylistKey
import com.metrolist.music.constants.ShowLikedPlaylistKey
import com.metrolist.music.constants.ShowTopPlaylistKey
import com.metrolist.music.constants.ShowUploadedPlaylistKey
import com.metrolist.music.constants.SliderStyle
import com.metrolist.music.constants.SliderStyleKey
import com.metrolist.music.constants.SlimNavBarKey
import com.metrolist.music.constants.SquigglySliderKey
import com.metrolist.music.constants.SwipeSensitivityKey
import com.metrolist.music.constants.SwipeThumbnailKey
import com.metrolist.music.constants.SwipeToRemoveSongKey
import com.metrolist.music.constants.SwipeToSongKey
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.NavigationBarTabsDialog
import com.metrolist.music.ui.component.PlayerSliderTrack
import com.metrolist.music.ui.component.SquigglySlider
import com.metrolist.music.ui.component.UsageModeOnboardingDialog
import com.metrolist.music.ui.component.WavySlider
import com.metrolist.music.ui.component.label
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.ui.theme.PlayerSliderColors
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.AppIcon
import com.metrolist.music.utils.IconUtils
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    navController: NavController,
    activity: Activity,
    snackbarHostState: SnackbarHostState,
) {
    val (dynamicTheme, onDynamicThemeChange) =
        rememberPreference(
            DynamicThemeKey,
            defaultValue = true,
        )
    val (enableHighRefreshRate, onEnableHighRefreshRateChange) =
        rememberPreference(
            EnableHighRefreshRateKey,
            defaultValue = false,
        )
    val (enableLandscapeScaling, onEnableLandscapeScalingChange) =
        rememberPreference(
            EnableLandscapeScalingKey,
            defaultValue = false,
        )
    val (selectedThemeColorInt) =
        rememberPreference(
            SelectedThemeColorKey,
            defaultValue = DefaultThemeColor.toArgb(),
        )
    // Check if user has selected a custom color (not the default/dynamic color)
    val isUsingCustomColor = selectedThemeColorInt != DefaultThemeColor.toArgb()

    val (miniPlayerBackground, onMiniPlayerBackgroundChange) =
        rememberEnumPreference(
            MiniPlayerBackgroundStyleKey,
            defaultValue = MiniPlayerBackgroundStyle.DEFAULT,
        )

    val availableMiniPlayerBackgroundStyles =
        MiniPlayerBackgroundStyle.entries.filter {
            it != MiniPlayerBackgroundStyle.BLUR || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

    var showMiniPlayerBackgroundDialog by rememberSaveable { mutableStateOf(false) }

    val (hidePlayerThumbnail, onHidePlayerThumbnailChange) =
        rememberPreference(
            HidePlayerThumbnailKey,
            defaultValue = false,
        )
    val (cropAlbumArt, onCropAlbumArtChange) =
        rememberPreference(
            CropAlbumArtKey,
            defaultValue = false,
        )
    val (playerBackground, onPlayerBackgroundChange) =
        rememberEnumPreference(
            PlayerBackgroundStyleKey,
            defaultValue = PlayerBackgroundStyle.BLUR,
        )

    val (defaultOpenTab, onDefaultOpenTabChange) =
        rememberEnumPreference(
            DefaultOpenTabKey,
            defaultValue = NavigationTab.HOME,
        )
    val (appUsageModeRaw) =
        rememberPreference(
            AppUsageModeKey,
            defaultValue = AppUsageMode.MUSIC_AND_PODCASTS.name,
        )
    val usageMode = remember(appUsageModeRaw) { resolveAppUsageMode(appUsageModeRaw) }
    var showUsageModeDialog by rememberSaveable { mutableStateOf(false) }
    val (navigationBarTabsRaw, onNavigationBarTabsChange) =
        rememberPreference(
            NavigationBarTabsKey,
            defaultValue = "",
        )
    val visibleNavTabs =
        remember(navigationBarTabsRaw) {
            deserializeNavigationTabs(navigationBarTabsRaw)
                .filter { it.second }
                .map { it.first }
        }
    val (playerButtonsStyle, onPlayerButtonsStyleChange) =
        rememberEnumPreference(
            PlayerButtonsStyleKey,
            defaultValue = PlayerButtonsStyle.DEFAULT,
        )
    val (rawLyricsAnimationStyle) =
        rememberPreference(
            LyricsAnimationStyleKey,
            defaultValue = LyricsAnimationStyle.APPLE.name,
        )
    val lyricsAnimationStyle =
        remember(rawLyricsAnimationStyle) {
            LyricsAnimationStyle.fromStoredValue(rawLyricsAnimationStyle)
        }
    val (lyricsTextSize) = rememberPreference(LyricsTextSizeKey, defaultValue = DEFAULT_LYRICS_TEXT_SIZE)

    val (sliderStyle, onSliderStyleChange) =
        rememberEnumPreference(
            SliderStyleKey,
            defaultValue = SliderStyle.DEFAULT,
        )
    val (squigglySlider, onSquigglySliderChange) =
        rememberPreference(
            SquigglySliderKey,
            defaultValue = false,
        )
    val (swipeThumbnail, onSwipeThumbnailChange) =
        rememberPreference(
            SwipeThumbnailKey,
            defaultValue = true,
        )
    val (swipeSensitivity, onSwipeSensitivityChange) =
        rememberPreference(
            SwipeSensitivityKey,
            defaultValue = 0.73f,
        )
    val (gridItemSize, onGridItemSizeChange) =
        rememberEnumPreference(
            GridItemsSizeKey,
            defaultValue = GridItemSize.BIG,
        )

    val (slimNav, onSlimNavChange) =
        rememberPreference(
            SlimNavBarKey,
            defaultValue = false,
        )

    val (rawAppIcon, onAppIconChange) =
        rememberPreference(
            AppIconKey,
            defaultValue = AppIcon.DEFAULT.name,
        )
    val currentAppIcon = remember(rawAppIcon) { AppIcon.fromStoredValue(rawAppIcon) }
    var showAppIconDialog by rememberSaveable { mutableStateOf(false) }

    // Density scale preferences
    val context = activity as Context
    val sharedPreferences = remember { context.getSharedPreferences("metrolist_settings", Context.MODE_PRIVATE) }
    val prefDensityScale =
        remember(sharedPreferences) {
            sharedPreferences.getFloat("density_scale_factor", 1.0f)
        }
    val (densityScale, setDensityScale) = rememberPreference(DensityScaleKey, defaultValue = prefDensityScale)
    var showRestartDialog by rememberSaveable { mutableStateOf(false) }
    var showDensityScaleDialog by rememberSaveable { mutableStateOf(false) }

    val onDensityScaleChange: (Float) -> Unit = { newScale ->
        setDensityScale(newScale)
        // Write to SharedPreferences for DensityScaler to read on next startup
        sharedPreferences.edit {
            putFloat("density_scale_factor", newScale)
        }
        showRestartDialog = true
    }

    val (swipeToSong, onSwipeToSongChange) =
        rememberPreference(
            SwipeToSongKey,
            defaultValue = false,
        )

    val (swipeToRemoveSong, onSwipeToRemoveSongChange) =
        rememberPreference(
            SwipeToRemoveSongKey,
            defaultValue = false,
        )

    val (showLikedPlaylist, onShowLikedPlaylistChange) =
        rememberPreference(
            ShowLikedPlaylistKey,
            defaultValue = true,
        )
    val (showDownloadedPlaylist, onShowDownloadedPlaylistChange) =
        rememberPreference(
            ShowDownloadedPlaylistKey,
            defaultValue = true,
        )
    val (showTopPlaylist, onShowTopPlaylistChange) =
        rememberPreference(
            ShowTopPlaylistKey,
            defaultValue = true,
        )
    val (showCachedPlaylist, onShowCachedPlaylistChange) =
        rememberPreference(
            ShowCachedPlaylistKey,
            defaultValue = true,
        )
    val (showUploadedPlaylist, onShowUploadedPlaylistChange) =
        rememberPreference(
            ShowUploadedPlaylistKey,
            defaultValue = true,
        )

    val availableBackgroundStyles =
        PlayerBackgroundStyle.entries.filter {
            it != PlayerBackgroundStyle.BLUR || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

    val (defaultChip, onDefaultChipChange) =
        rememberEnumPreference(
            key = ChipSortTypeKey,
            defaultValue = LibraryFilter.LIBRARY,
        )

    var showSliderOptionDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showPlayerBackgroundDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showPlayerButtonsStyleDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showPlayerButtonsStyleDialog) {
        EnumDialog(
            onDismiss = { showPlayerButtonsStyleDialog = false },
            onSelect = {
                onPlayerButtonsStyleChange(it)
                showPlayerButtonsStyleDialog = false
            },
            title = stringResource(R.string.player_buttons_style),
            current = playerButtonsStyle,
            values = PlayerButtonsStyle.values().toList(),
            valueText = {
                when (it) {
                    PlayerButtonsStyle.DEFAULT -> stringResource(R.string.default_style)
                    PlayerButtonsStyle.PRIMARY -> stringResource(R.string.primary_color_style)
                    PlayerButtonsStyle.TERTIARY -> stringResource(R.string.tertiary_color_style)
                }
            },
        )
    }

    if (showPlayerBackgroundDialog) {
        EnumDialog(
            onDismiss = { showPlayerBackgroundDialog = false },
            onSelect = {
                onPlayerBackgroundChange(it)
                showPlayerBackgroundDialog = false
            },
            title = stringResource(R.string.player_background_style),
            current = playerBackground,
            values = availableBackgroundStyles,
            valueText = {
                when (it) {
                    PlayerBackgroundStyle.DEFAULT -> stringResource(R.string.follow_theme)
                    PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                    PlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                }
            },
        )
    }

    if (showMiniPlayerBackgroundDialog) {
        EnumDialog(
            onDismiss = { showMiniPlayerBackgroundDialog = false },
            onSelect = {
                onMiniPlayerBackgroundChange(it)
                showMiniPlayerBackgroundDialog = false
            },
            title = stringResource(R.string.mini_player_background_style),
            current = miniPlayerBackground,
            values = availableMiniPlayerBackgroundStyles,
            valueText = {
                when (it) {
                    MiniPlayerBackgroundStyle.DEFAULT -> stringResource(R.string.follow_theme)
                    MiniPlayerBackgroundStyle.TRANSPARENT -> stringResource(R.string.transparent)
                    MiniPlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                    MiniPlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                    MiniPlayerBackgroundStyle.PURE_BLACK -> stringResource(R.string.pure_black)
                }
            },
        )
    }

    var showNavigationBarTabsDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val appearanceScope = rememberCoroutineScope()
    val podcastsOnlyNotice = stringResource(R.string.usage_mode_podcasts_only_enabled_notice)
    if (showUsageModeDialog) {
        UsageModeOnboardingDialog(
            isOnboarding = false,
            initialMode = usageMode,
            onConfirm = { mode ->
                showUsageModeDialog = false
                appearanceScope.launch {
                    context.safeDataStoreEdit { settings ->
                        applyAppUsageMode(settings, mode)
                    }
                    if (mode == AppUsageMode.PODCASTS_ONLY) {
                        snackbarHostState.showSnackbar(
                            message = podcastsOnlyNotice,
                            duration = SnackbarDuration.Long,
                        )
                    }
                }
            },
            onDismiss = { showUsageModeDialog = false },
        )
    }

    if (showNavigationBarTabsDialog) {
        NavigationBarTabsDialog(
            initialTabsRaw = navigationBarTabsRaw,
            onDismiss = { showNavigationBarTabsDialog = false },
            onSave = { newRaw ->
                onNavigationBarTabsChange(newRaw)
                val newVisibleTabs =
                    deserializeNavigationTabs(newRaw)
                        .filter { it.second }
                        .map { it.first }
                if (defaultOpenTab !in newVisibleTabs && newVisibleTabs.isNotEmpty()) {
                    onDefaultOpenTabChange(newVisibleTabs.first())
                }
            },
        )
    }

    var showDefaultOpenTabDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showDefaultOpenTabDialog) {
        val selectableTabs = visibleNavTabs.ifEmpty { NavigationTab.values().toList() }
        val currentSelectedTab = if (defaultOpenTab in selectableTabs) defaultOpenTab else selectableTabs.first()
        EnumDialog(
            onDismiss = { showDefaultOpenTabDialog = false },
            onSelect = {
                onDefaultOpenTabChange(it)
                showDefaultOpenTabDialog = false
            },
            title = stringResource(R.string.default_open_tab),
            current = currentSelectedTab,
            values = selectableTabs,
            valueText = {
                when (it) {
                    NavigationTab.HOME -> stringResource(R.string.home)
                    NavigationTab.SEARCH -> stringResource(R.string.search)
                    NavigationTab.PODCAST -> stringResource(R.string.podcast)
                    NavigationTab.LIBRARY -> stringResource(R.string.filter_library)
                }
            },
        )
    }

    var showDefaultChipDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showDefaultChipDialog) {
        EnumDialog(
            onDismiss = { showDefaultChipDialog = false },
            onSelect = {
                onDefaultChipChange(it)
                showDefaultChipDialog = false
            },
            title = stringResource(R.string.default_lib_chips),
            current = defaultChip,
            values = LibraryFilter.values().toList(),
            valueText = {
                when (it) {
                    LibraryFilter.SONGS -> stringResource(R.string.songs)
                    LibraryFilter.ARTISTS -> stringResource(R.string.artists)
                    LibraryFilter.ALBUMS -> stringResource(R.string.albums)
                    LibraryFilter.PLAYLISTS -> stringResource(R.string.playlists)
                    LibraryFilter.PODCASTS -> stringResource(R.string.filter_podcasts)
                    LibraryFilter.LIBRARY -> stringResource(R.string.filter_library)
                }
            },
        )
    }

    var showGridSizeDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showGridSizeDialog) {
        EnumDialog(
            onDismiss = { showGridSizeDialog = false },
            onSelect = {
                onGridItemSizeChange(it)
                showGridSizeDialog = false
            },
            title = stringResource(R.string.grid_cell_size),
            current = gridItemSize,
            values = GridItemSize.values().toList(),
            valueText = {
                when (it) {
                    GridItemSize.BIG -> stringResource(R.string.big)
                    GridItemSize.SMALL -> stringResource(R.string.small)
                }
            },
        )
    }

    if (showAppIconDialog) {
        DefaultDialog(
            onDismiss = { showAppIconDialog = false },
            buttons = {
                TextButton(
                    onClick = { showAppIconDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_icon),
                    style = MaterialTheme.typography.titleLarge,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon.entries.forEach { icon ->
                        val selected = icon == currentAppIcon
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
                                        },
                                    )
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color =
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            },
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                    .clickable {
                                        onAppIconChange(icon.name)
                                        IconUtils.setIcon(context, icon)
                                        showAppIconDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            AppIconPreviewBox(icon = icon, size = 48.dp)
                            Text(
                                text = stringResource(icon.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    painter = painterResource(R.drawable.check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRestartDialog) {
        DefaultDialog(
            onDismiss = { showRestartDialog = false },
            buttons = {
                TextButton(
                    onClick = { showRestartDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showRestartDialog = false
                        val intent =
                            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        context.startActivity(intent)
                        Runtime.getRuntime().exit(0)
                    },
                ) {
                    Text(text = stringResource(R.string.restart))
                }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.restart_required),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.density_restart_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showDensityScaleDialog) {
        DefaultDialog(
            onDismiss = { showDensityScaleDialog = false },
            buttons = {
                TextButton(
                    onClick = { showDensityScaleDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        ) {
            Column {
                DensityScale.entries.forEach { scale ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDensityScaleChange(scale.value)
                                    showDensityScaleDialog = false
                                }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = scale.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                if (densityScale == scale.value) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            }
        }
    }

    if (showSliderOptionDialog) {
        DefaultDialog(
            buttons = {
                TextButton(
                    onClick = { showSliderOptionDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                showSliderOptionDialog = false
            },
        ) {
            val sliderPreviewColors =
                PlayerSliderColors.getSliderColors(
                    MaterialTheme.colorScheme.primary,
                    PlayerBackgroundStyle.DEFAULT,
                    isSystemInDarkTheme(),
                )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .aspectRatio(1f)
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    if (sliderStyle == SliderStyle.DEFAULT &&
                                        !squigglySlider
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    RoundedCornerShape(16.dp),
                                ).clickable {
                                    onSliderStyleChange(SliderStyle.DEFAULT)
                                    onSquigglySliderChange(false)
                                    showSliderOptionDialog = false
                                }.padding(12.dp),
                    ) {
                        val sliderValue = 0.35f
                        Slider(
                            value = sliderValue,
                            valueRange = 0f..1f,
                            onValueChange = { /* preview only */ },
                            colors = sliderPreviewColors,
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.default_),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .aspectRatio(1f)
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    if (sliderStyle == SliderStyle.WAVY &&
                                        !squigglySlider
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    RoundedCornerShape(16.dp),
                                ).clickable {
                                    onSliderStyleChange(SliderStyle.WAVY)
                                    onSquigglySliderChange(false)
                                    showSliderOptionDialog = false
                                }.padding(12.dp),
                    ) {
                        val sliderValue = 0.5f
                        WavySlider(
                            value = sliderValue,
                            valueRange = 0f..1f,
                            onValueChange = { /* preview only */ },
                            colors = sliderPreviewColors,
                            modifier = Modifier.weight(1f),
                            isPlaying = true,
                            enabled = false,
                        )
                        Text(
                            text = stringResource(R.string.wavy),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .aspectRatio(1f)
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    if (sliderStyle ==
                                        SliderStyle.SLIM
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    RoundedCornerShape(16.dp),
                                ).clickable {
                                    onSliderStyleChange(SliderStyle.SLIM)
                                    onSquigglySliderChange(false)
                                    showSliderOptionDialog = false
                                }.padding(12.dp),
                    ) {
                        val sliderValue = 0.65f
                        Slider(
                            value = sliderValue,
                            valueRange = 0f..1f,
                            onValueChange = { /* preview only */ },
                            thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                            track = { sliderState ->
                                PlayerSliderTrack(
                                    sliderState = sliderState,
                                    colors = sliderPreviewColors,
                                )
                            },
                            colors = sliderPreviewColors,
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        )

                        Text(
                            text = stringResource(R.string.slim),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .aspectRatio(1f)
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    if (sliderStyle == SliderStyle.WAVY &&
                                        squigglySlider
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    RoundedCornerShape(16.dp),
                                ).clickable {
                                    onSliderStyleChange(SliderStyle.WAVY)
                                    onSquigglySliderChange(true)
                                    showSliderOptionDialog = false
                                }.padding(12.dp),
                    ) {
                        val sliderValue = 0.5f
                        SquigglySlider(
                            value = sliderValue,
                            valueRange = 0f..1f,
                            onValueChange = { /* preview only */ },
                            modifier = Modifier.weight(1f),
                            enabled = false,
                            colors = sliderPreviewColors,
                            isPlaying = true,
                        )
                        Text(
                            text = stringResource(R.string.squiggly),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.theme),
            items =
                buildList {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.speed),
                            title = { Text(stringResource(R.string.enable_high_refresh_rate)) },
                            description = { Text(stringResource(R.string.enable_high_refresh_rate_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = enableHighRefreshRate,
                                    onCheckedChange = onEnableHighRefreshRateChange,
                                    thumbContent = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    id = if (enableHighRefreshRate) R.drawable.check else R.drawable.close,
                                                ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                )
                            },
                            onClick = { onEnableHighRefreshRateChange(!enableHighRefreshRate) },
                        ),
                    )
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.fullscreen),
                            title = { Text(stringResource(R.string.enable_landscape_scaling)) },
                            description = { Text(stringResource(R.string.enable_landscape_scaling_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = enableLandscapeScaling,
                                    onCheckedChange = onEnableLandscapeScalingChange,
                                    thumbContent = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    id = if (enableLandscapeScaling) R.drawable.check else R.drawable.close,
                                                ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                )
                            },
                            onClick = { onEnableLandscapeScalingChange(!enableLandscapeScaling) },
                        ),
                    )
                    // Only show dynamic theme option when using the default/dynamic color
                    // When a custom color is selected, dynamic theme is automatically disabled
                    if (!isUsingCustomColor) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.palette),
                                title = { Text(stringResource(R.string.enable_dynamic_theme)) },
                                trailingContent = {
                                    Switch(
                                        checked = dynamicTheme,
                                        onCheckedChange = onDynamicThemeChange,
                                        thumbContent = {
                                            Icon(
                                                painter =
                                                    painterResource(
                                                        id = if (dynamicTheme) R.drawable.check else R.drawable.close,
                                                    ),
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        },
                                    )
                                },
                                onClick = { onDynamicThemeChange(!dynamicTheme) },
                            ),
                        )
                    }
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.palette),
                            title = { Text(stringResource(R.string.theme)) },
                            description = { Text(stringResource(R.string.theme_desc)) },
                            onClick = { navController.navigate("settings/appearance/theme") },
                        ),
                    )
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.palette),
                            title = { Text(stringResource(R.string.app_icon)) },
                            description = { Text(stringResource(currentAppIcon.labelRes)) },
                            trailingContent = { AppIconPreviewBox(icon = currentAppIcon, size = 36.dp) },
                            onClick = { showAppIconDialog = true },
                        ),
                    )
                },
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(id = R.string.mini_player),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.gradient),
                        title = { Text(stringResource(R.string.mini_player_background_style)) },
                        description = {
                            Text(
                                when (miniPlayerBackground) {
                                    MiniPlayerBackgroundStyle.DEFAULT -> stringResource(R.string.follow_theme)
                                    MiniPlayerBackgroundStyle.TRANSPARENT -> stringResource(R.string.transparent)
                                    MiniPlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                                    MiniPlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                                    MiniPlayerBackgroundStyle.PURE_BLACK -> stringResource(R.string.pure_black)
                                },
                            )
                        },
                        onClick = { showMiniPlayerBackgroundDialog = true },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(27.dp))

        var showSensitivityDialog by rememberSaveable { mutableStateOf(false) }

        Material3SettingsGroup(
            title = stringResource(R.string.player),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.gradient),
                        title = { Text(stringResource(R.string.player_background_style)) },
                        description = {
                            Text(
                                when (playerBackground) {
                                    PlayerBackgroundStyle.DEFAULT -> stringResource(R.string.follow_theme)
                                    PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                                    PlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                                },
                            )
                        },
                        onClick = { showPlayerBackgroundDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.hide_image),
                        title = { Text(stringResource(R.string.hide_player_thumbnail)) },
                        description = { Text(stringResource(R.string.hide_player_thumbnail_desc)) },
                        trailingContent = {
                            Switch(
                                checked = hidePlayerThumbnail,
                                onCheckedChange = onHidePlayerThumbnailChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (hidePlayerThumbnail) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onHidePlayerThumbnailChange(!hidePlayerThumbnail) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.crop),
                        title = { Text(stringResource(R.string.crop_album_art)) },
                        description = { Text(stringResource(R.string.crop_album_art_desc)) },
                        trailingContent = {
                            Switch(
                                checked = cropAlbumArt,
                                onCheckedChange = onCropAlbumArtChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (cropAlbumArt) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onCropAlbumArtChange(!cropAlbumArt) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.palette),
                        title = { Text(stringResource(R.string.player_buttons_style)) },
                        description = {
                            Text(
                                when (playerButtonsStyle) {
                                    PlayerButtonsStyle.DEFAULT -> stringResource(R.string.default_style)
                                    PlayerButtonsStyle.PRIMARY -> stringResource(R.string.primary_color_style)
                                    PlayerButtonsStyle.TERTIARY -> stringResource(R.string.tertiary_color_style)
                                },
                            )
                        },
                        onClick = { showPlayerButtonsStyleDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.sliders),
                        title = { Text(stringResource(R.string.player_slider_style)) },
                        description = {
                            Text(
                                when (sliderStyle) {
                                    SliderStyle.DEFAULT -> {
                                        stringResource(R.string.default_)
                                    }

                                    SliderStyle.WAVY -> {
                                        if (squigglySlider) {
                                            stringResource(R.string.squiggly)
                                        } else {
                                            stringResource(
                                                R.string.wavy,
                                            )
                                        }
                                    }

                                    SliderStyle.SLIM -> {
                                        stringResource(R.string.slim)
                                    }
                                },
                            )
                        },
                        onClick = { showSliderOptionDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.swipe),
                        title = { Text(stringResource(R.string.enable_swipe_thumbnail)) },
                        trailingContent = {
                            Switch(
                                checked = swipeThumbnail,
                                onCheckedChange = onSwipeThumbnailChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (swipeThumbnail) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onSwipeThumbnailChange(!swipeThumbnail) },
                    ),
                ) +
                    if (swipeThumbnail) {
                        listOf(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.tune),
                                title = { Text(stringResource(R.string.swipe_sensitivity)) },
                                description = {
                                    Text(
                                        stringResource(
                                            R.string.sensitivity_percentage,
                                            (swipeSensitivity * 100).roundToInt(),
                                        ),
                                    )
                                },
                                onClick = { showSensitivityDialog = true },
                            ),
                        )
                    } else {
                        emptyList()
                    },
        )

        if (showSensitivityDialog) {
            var tempSensitivity by remember { mutableFloatStateOf(swipeSensitivity) }

            DefaultDialog(
                onDismiss = {
                    tempSensitivity = swipeSensitivity
                    showSensitivityDialog = false
                },
                buttons = {
                    TextButton(
                        onClick = {
                            tempSensitivity = 0.73f
                        },
                    ) {
                        Text(stringResource(R.string.reset))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            tempSensitivity = swipeSensitivity
                            showSensitivityDialog = false
                        },
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onSwipeSensitivityChange(tempSensitivity)
                            showSensitivityDialog = false
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.swipe_sensitivity),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Text(
                        text =
                            stringResource(
                                R.string.sensitivity_percentage,
                                (tempSensitivity * 100).roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Slider(
                        value = tempSensitivity,
                        onValueChange = { tempSensitivity = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.artwork),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.slow_motion_video),
                        title = { Text(stringResource(R.string.artwork)) },
                        description = { Text(stringResource(R.string.settings_artwork_overview)) },
                        onClick = { navController.navigate("settings/appearance/artwork") },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.lyrics)) },
                        description = {
                            Text(
                                "${lyricsAnimationStyleLabel(lyricsAnimationStyle)} · ${lyricsTextSize.coerceIn(LYRICS_TEXT_SIZE_RANGE).roundToInt()} sp",
                            )
                        },
                        onClick = { navController.navigate("settings/appearance/lyrics") },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.misc),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(
                            if (usageMode == AppUsageMode.PODCASTS_ONLY) R.drawable.podcast else R.drawable.music_note,
                        ),
                        title = { Text(stringResource(R.string.app_usage_mode)) },
                        description = {
                            Text(
                                stringResource(
                                    if (usageMode == AppUsageMode.PODCASTS_ONLY) {
                                        R.string.usage_mode_podcasts_only
                                    } else {
                                        R.string.usage_mode_music_and_podcasts
                                    },
                                ),
                            )
                        },
                        onClick = { showUsageModeDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.nav_bar),
                        title = { Text(stringResource(R.string.navigation_bar_tabs)) },
                        description = {
                            val tabsSummary =
                                visibleNavTabs
                                    .map { it.label() }
                                    .joinToString(" · ")
                            Text(tabsSummary)
                        },
                        onClick = { showNavigationBarTabsDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.nav_bar),
                        title = { Text(stringResource(R.string.default_open_tab)) },
                        description = {
                            val effectiveOpenTab =
                                if (defaultOpenTab in visibleNavTabs) {
                                    defaultOpenTab
                                } else {
                                    visibleNavTabs.firstOrNull() ?: defaultOpenTab
                                }
                            Text(effectiveOpenTab.label())
                        },
                        onClick = { showDefaultOpenTabDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.tab),
                        title = { Text(stringResource(R.string.default_lib_chips)) },
                        description = {
                            Text(
                                when (defaultChip) {
                                    LibraryFilter.SONGS -> stringResource(R.string.songs)
                                    LibraryFilter.ARTISTS -> stringResource(R.string.artists)
                                    LibraryFilter.ALBUMS -> stringResource(R.string.albums)
                                    LibraryFilter.PLAYLISTS -> stringResource(R.string.playlists)
                                    LibraryFilter.PODCASTS -> stringResource(R.string.filter_podcasts)
                                    LibraryFilter.LIBRARY -> stringResource(R.string.filter_library)
                                },
                            )
                        },
                        onClick = { showDefaultChipDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.swipe),
                        title = { Text(stringResource(R.string.swipe_song_to_add)) },
                        trailingContent = {
                            Switch(
                                checked = swipeToSong,
                                onCheckedChange = onSwipeToSongChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (swipeToSong) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onSwipeToSongChange(!swipeToSong) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.swipe),
                        title = { Text(stringResource(R.string.swipe_song_to_remove)) },
                        trailingContent = {
                            Switch(
                                checked = swipeToRemoveSong,
                                onCheckedChange = onSwipeToRemoveSongChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (swipeToRemoveSong) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onSwipeToRemoveSongChange(!swipeToRemoveSong) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.nav_bar),
                        title = { Text(stringResource(R.string.slim_navbar)) },
                        trailingContent = {
                            Switch(
                                checked = slimNav,
                                onCheckedChange = onSlimNavChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (slimNav) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onSlimNavChange(!slimNav) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.grid_view),
                        title = { Text(stringResource(R.string.grid_cell_size)) },
                        description = {
                            Text(
                                when (gridItemSize) {
                                    GridItemSize.BIG -> stringResource(R.string.big)
                                    GridItemSize.SMALL -> stringResource(R.string.small)
                                },
                            )
                        },
                        onClick = { showGridSizeDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.grid_view),
                        title = { Text(stringResource(R.string.display_density)) },
                        description = {
                            Text(DensityScale.fromValue(densityScale).label)
                        },
                        onClick = { showDensityScaleDialog = true },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.auto_playlists),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.favorite),
                        title = { Text(stringResource(R.string.show_liked_playlist)) },
                        trailingContent = {
                            Switch(
                                checked = showLikedPlaylist,
                                onCheckedChange = onShowLikedPlaylistChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (showLikedPlaylist) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onShowLikedPlaylistChange(!showLikedPlaylist) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.offline),
                        title = { Text(stringResource(R.string.show_downloaded_playlist)) },
                        trailingContent = {
                            Switch(
                                checked = showDownloadedPlaylist,
                                onCheckedChange = onShowDownloadedPlaylistChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (showDownloadedPlaylist) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onShowDownloadedPlaylistChange(!showDownloadedPlaylist) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.trending_up),
                        title = { Text(stringResource(R.string.show_top_playlist)) },
                        trailingContent = {
                            Switch(
                                checked = showTopPlaylist,
                                onCheckedChange = onShowTopPlaylistChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (showTopPlaylist) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onShowTopPlaylistChange(!showTopPlaylist) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.cached),
                        title = { Text(stringResource(R.string.show_cached_playlist)) },
                        trailingContent = {
                            Switch(
                                checked = showCachedPlaylist,
                                onCheckedChange = onShowCachedPlaylistChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (showCachedPlaylist) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onShowCachedPlaylistChange(!showCachedPlaylist) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.backup),
                        title = { Text(stringResource(R.string.show_uploaded_playlist)) },
                        trailingContent = {
                            Switch(
                                checked = showUploadedPlaylist,
                                onCheckedChange = onShowUploadedPlaylistChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (showUploadedPlaylist) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onShowUploadedPlaylistChange(!showUploadedPlaylist) },
                    ),
                ),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.appearance)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun AppIconPreviewBox(
    icon: AppIcon,
    size: Dp,
) {
    val shape = RoundedCornerShape(size * 0.26f)
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(shape)
                .border(
                    width = 0.75.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    shape = shape,
                ),
    ) {
        Image(
            painter = painterResource(icon.previewBackgroundRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
        Image(
            painter = painterResource(icon.previewForegroundRes),
            contentDescription = null,
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1.25f
                        scaleY = 1.25f
                    },
        )
    }
}

enum class DarkMode {
    ON,
    OFF,
    AUTO,
}

enum class NavigationTab {
    HOME,
    SEARCH,
    PODCAST,
    LIBRARY,
}

enum class LyricsPosition {
    LEFT,
    CENTER,
    RIGHT,
}

enum class PlayerTextAlignment {
    SIDED,
    CENTER,
}

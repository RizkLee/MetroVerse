/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AnimatedCoverResolution
import com.metrolist.music.constants.AnimatedCoverResolutionKey
import com.metrolist.music.constants.AppleMusicArtistVideoResolutionKey
import com.metrolist.music.constants.DisableAnimatedArtworkOnMobileDataKey
import com.metrolist.music.constants.EnableAnimatedCoversKey
import com.metrolist.music.constants.EnableAppleMusicArtistImagesKey
import com.metrolist.music.constants.EnableAppleMusicArtistVideosKey
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

@Composable
private fun animatedCoverResolutionLabel(resolution: AnimatedCoverResolution): String =
    when (resolution) {
        AnimatedCoverResolution.LOW_360 -> stringResource(R.string.animated_cover_resolution_low)
        AnimatedCoverResolution.MEDIUM_720 -> stringResource(R.string.animated_cover_resolution_medium)
        AnimatedCoverResolution.HIGH_1080 -> stringResource(R.string.animated_cover_resolution_high)
        AnimatedCoverResolution.ORIGINAL -> stringResource(R.string.animated_cover_resolution_original)
    }

@Composable
private fun disabledLabelColor(enabled: Boolean) =
    if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

@Composable
private fun disabledSummaryColor(enabled: Boolean) =
    if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkAppearanceSettings(navController: NavController) {
    val (enableAnimatedCovers, onEnableAnimatedCoversChange) =
        rememberPreference(EnableAnimatedCoversKey, defaultValue = false)
    val (disableAnimatedOnMobileData, onDisableAnimatedOnMobileDataChange) =
        rememberPreference(DisableAnimatedArtworkOnMobileDataKey, defaultValue = false)
    val (animatedCoverResolution, onAnimatedCoverResolutionChange) =
        rememberEnumPreference(AnimatedCoverResolutionKey, defaultValue = AnimatedCoverResolution.MEDIUM_720)

    val (enableAppleMusicArtistImages, onEnableAppleMusicArtistImagesChange) =
        rememberPreference(EnableAppleMusicArtistImagesKey, defaultValue = false)
    val (enableAppleMusicArtistVideos, onEnableAppleMusicArtistVideosChange) =
        rememberPreference(EnableAppleMusicArtistVideosKey, defaultValue = false)
    val (artistVideoResolution, onArtistVideoResolutionChange) =
        rememberEnumPreference(AppleMusicArtistVideoResolutionKey, defaultValue = AnimatedCoverResolution.MEDIUM_720)

    var showCoverResolutionDialog by rememberSaveable { mutableStateOf(false) }
    var showArtistVideoResolutionDialog by rememberSaveable { mutableStateOf(false) }

    if (showCoverResolutionDialog) {
        EnumDialog(
            onDismiss = { showCoverResolutionDialog = false },
            onSelect = {
                onAnimatedCoverResolutionChange(it)
                showCoverResolutionDialog = false
            },
            title = stringResource(R.string.animated_cover_resolution),
            current = animatedCoverResolution,
            values = AnimatedCoverResolution.entries,
            valueText = { animatedCoverResolutionLabel(it) },
        )
    }

    if (showArtistVideoResolutionDialog) {
        EnumDialog(
            onDismiss = { showArtistVideoResolutionDialog = false },
            onSelect = {
                onArtistVideoResolutionChange(it)
                showArtistVideoResolutionDialog = false
            },
            title = stringResource(R.string.apple_music_artist_video_quality_title),
            current = artistVideoResolution,
            values = AnimatedCoverResolution.entries,
            valueText = { animatedCoverResolutionLabel(it) },
        )
    }

    Column(
        modifier =
            Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.player),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.slow_motion_video),
                        title = { Text(stringResource(R.string.enable_animated_covers)) },
                        description = { Text(stringResource(R.string.enable_animated_covers_desc)) },
                        trailingContent = {
                            Switch(
                                checked = enableAnimatedCovers,
                                onCheckedChange = onEnableAnimatedCoversChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (enableAnimatedCovers) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onEnableAnimatedCoversChange(!enableAnimatedCovers) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.wifi_proxy),
                        title = { Text(stringResource(R.string.disable_animated_artwork_on_mobile_data)) },
                        description = { Text(stringResource(R.string.disable_animated_artwork_on_mobile_data_desc)) },
                        trailingContent = {
                            Switch(
                                checked = disableAnimatedOnMobileData,
                                onCheckedChange = onDisableAnimatedOnMobileDataChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (disableAnimatedOnMobileData) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onDisableAnimatedOnMobileDataChange(!disableAnimatedOnMobileData) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.tune),
                        title = {
                            Text(
                                text = stringResource(R.string.animated_cover_resolution),
                                color = disabledLabelColor(enableAnimatedCovers),
                            )
                        },
                        description = {
                            Text(
                                text = animatedCoverResolutionLabel(animatedCoverResolution),
                                color = disabledSummaryColor(enableAnimatedCovers),
                            )
                        },
                        enabled = enableAnimatedCovers,
                        onClick = { if (enableAnimatedCovers) showCoverResolutionDialog = true },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.artist_artwork_section),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.insert_photo),
                        title = { Text(stringResource(R.string.apple_music_artist_images_title)) },
                        description = { Text(stringResource(R.string.apple_music_artist_images_description)) },
                        trailingContent = {
                            Switch(
                                checked = enableAppleMusicArtistImages,
                                onCheckedChange = onEnableAppleMusicArtistImagesChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (enableAppleMusicArtistImages) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onEnableAppleMusicArtistImagesChange(!enableAppleMusicArtistImages) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.slow_motion_video),
                        title = { Text(stringResource(R.string.apple_music_artist_videos_title)) },
                        description = { Text(stringResource(R.string.apple_music_artist_videos_description)) },
                        trailingContent = {
                            Switch(
                                checked = enableAppleMusicArtistVideos,
                                onCheckedChange = onEnableAppleMusicArtistVideosChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (enableAppleMusicArtistVideos) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onEnableAppleMusicArtistVideosChange(!enableAppleMusicArtistVideos) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.tune),
                        title = {
                            Text(
                                text = stringResource(R.string.apple_music_artist_video_quality_title),
                                color = disabledLabelColor(enableAppleMusicArtistVideos),
                            )
                        },
                        description = {
                            Text(
                                text = animatedCoverResolutionLabel(artistVideoResolution),
                                color = disabledSummaryColor(enableAppleMusicArtistVideos),
                            )
                        },
                        enabled = enableAppleMusicArtistVideos,
                        onClick = { if (enableAppleMusicArtistVideos) showArtistVideoResolutionDialog = true },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.artwork)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

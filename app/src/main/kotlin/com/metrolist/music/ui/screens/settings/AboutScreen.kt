/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain

private const val METROVERSE_REPOSITORY = "https://github.com/Rizklee/MetroVerse"
private const val METROLIST_REPOSITORY = "https://github.com/MetrolistGroup/Metrolist"
private const val PODIUM_REPOSITORY = "https://github.com/aimok04/podium"
private const val APPLE_PODCASTS = "https://www.apple.com/apple-podcasts/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
) {
    val uriHandler = LocalUriHandler.current
    val windowInsets = LocalPlayerAwareWindowInsets.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(windowInsets.only(WindowInsetsSides.Top)))
        Spacer(Modifier.height(24.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_artwork),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(88.dp),
            )
            Column(modifier = Modifier.padding(start = 20.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.app_version_info, BuildConfig.VERSION_NAME, BuildConfig.ARCHITECTURE),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.metroverse_learning_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = stringResource(R.string.metroverse_summary),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
        )

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.metroverse_unofficial_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.metroverse_project),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.person),
                        title = { Text(stringResource(R.string.metroverse_maintainer)) },
                        description = { Text("Rizklee (@Rizklee)") },
                        onClick = { uriHandler.openUri("https://github.com/Rizklee") },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.github),
                        title = { Text(stringResource(R.string.credits_view_repo)) },
                        description = { Text("Rizklee/MetroVerse") },
                        onClick = { uriHandler.openUri(METROVERSE_REPOSITORY) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.update),
                        title = { Text(stringResource(R.string.metroverse_releases)) },
                        description = { Text(stringResource(R.string.metroverse_manual_updates)) },
                        onClick = { uriHandler.openUri("$METROVERSE_REPOSITORY/releases") },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.info),
                        title = { Text(stringResource(R.string.credits_license_name)) },
                        description = { Text(stringResource(R.string.metroverse_license_description)) },
                        onClick = { uriHandler.openUri("$METROVERSE_REPOSITORY/blob/main/LICENSE") },
                    ),
                ),
        )

        Spacer(Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.metroverse_lineage),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.music_note),
                        title = { Text("Metrolist") },
                        description = { Text(stringResource(R.string.metroverse_metrolist_relationship)) },
                        onClick = { uriHandler.openUri(METROLIST_REPOSITORY) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.podcast),
                        title = { Text("Podium") },
                        description = { Text(stringResource(R.string.metroverse_podium_relationship)) },
                        onClick = { uriHandler.openUri(PODIUM_REPOSITORY) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.podcast),
                        title = { Text("Apple Podcasts") },
                        description = { Text(stringResource(R.string.metroverse_apple_podcasts_attribution)) },
                        onClick = { uriHandler.openUri(APPLE_PODCASTS) },
                    ),
                ),
        )

        Spacer(Modifier.height(40.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
    )
}

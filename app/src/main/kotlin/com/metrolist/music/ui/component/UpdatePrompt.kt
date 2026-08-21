/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import com.metrolist.music.constants.CheckForUpdatesKey
import com.metrolist.music.constants.LastUpdateCheckTimeKey
import com.metrolist.music.utils.ReleaseInfo
import com.metrolist.music.utils.Updater
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

@Composable
fun AutomaticUpdateChecker(
    onLatestVersionNameChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val (checkForUpdates) = rememberPreference(CheckForUpdatesKey, defaultValue = true)
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

    LaunchedEffect(checkForUpdates) {
        if (!Updater.isSupportedBuild || !checkForUpdates) return@LaunchedEffect

        val now = System.currentTimeMillis()
        val lastCheckTime = context.dataStore.get(LastUpdateCheckTimeKey, 0L)
        if (now - lastCheckTime < AUTO_UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect

        context.safeDataStoreEdit { preferences ->
            preferences[LastUpdateCheckTimeKey] = now
        }
        withContext(Dispatchers.IO) {
            Updater.checkForUpdate(forceRefresh = true)
        }.onSuccess { (releaseInfo, hasUpdate) ->
            if (releaseInfo != null && hasUpdate) {
                onLatestVersionNameChange(releaseInfo.versionName)
                if (Updater.getAssetForCurrentVariant(releaseInfo) != null) {
                    availableRelease = releaseInfo
                }
            } else {
                onLatestVersionNameChange(BuildConfig.VERSION_NAME)
            }
        }
    }

    availableRelease?.let { releaseInfo ->
        UpdateDownloadDialog(
            releaseInfo = releaseInfo,
            onDismiss = { availableRelease = null },
        )
    }
}

@Composable
fun UpdateDownloadDialog(
    releaseInfo: ReleaseInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val asset = remember(releaseInfo) { Updater.getAssetForCurrentVariant(releaseInfo) }
    var isDownloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val downloadFailedTemplate = stringResource(R.string.update_download_failed)
    val permissionDeniedMessage = stringResource(R.string.update_install_permission_denied)

    val downloadAndInstall = {
        if (!isDownloading) {
            scope.launch {
                isDownloading = true
                errorMessage = null
                Updater
                    .downloadAndVerifyUpdate(context, releaseInfo)
                    .onSuccess { apkFile ->
                        runCatching {
                            context.startActivity(Updater.createInstallIntent(context, apkFile))
                        }.onSuccess {
                            onDismiss()
                        }.onFailure { error ->
                            errorMessage = String.format(downloadFailedTemplate, error.message ?: "Unknown error")
                        }
                    }.onFailure { error ->
                        errorMessage = String.format(downloadFailedTemplate, error.message ?: "Unknown error")
                    }
                isDownloading = false
            }
        }
    }

    val installPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (context.packageManager.canRequestPackageInstalls()) {
                downloadAndInstall()
            } else {
                errorMessage = permissionDeniedMessage
            }
        }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.update_version_and_variant,
                            releaseInfo.versionName,
                            asset?.variant?.uppercase().orEmpty(),
                        ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (releaseInfo.prerelease) {
                    Text(
                        text = stringResource(R.string.update_prerelease_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                releaseInfo.description.takeIf(String::isNotBlank)?.let { description ->
                    ReleaseNotesMarkdown(markdown = description)
                }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Text(
                        text = stringResource(R.string.update_install_permission_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDownloading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                        Text(stringResource(R.string.update_downloading_and_verifying))
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isDownloading && asset != null,
                onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) {
                        downloadAndInstall()
                    } else {
                        val intent =
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                "package:${context.packageName}".toUri(),
                            )
                        installPermissionLauncher.launch(intent)
                    }
                },
            ) {
                Text(stringResource(R.string.update_download_and_install))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isDownloading,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.update_not_now))
            }
        },
    )
}

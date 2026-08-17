/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ChipSortTypeKey
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.utils.FullSyncResult
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.viewmodels.LibraryMixViewModel
import com.metrolist.music.viewmodels.LibraryPodcastsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    snackbarHostState: SnackbarHostState,
    mixViewModel: LibraryMixViewModel = hiltViewModel(),
    podcastsViewModel: LibraryPodcastsViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val refreshOfflineMessage = stringResource(R.string.library_refresh_offline)
    val refreshLoginMessage = stringResource(R.string.library_refresh_login_required)
    val refreshFailedMessage = stringResource(R.string.library_refresh_failed)

    val filterContent = @Composable {
        Row {
            ChipsRow(
                chips = listOf(
                    LibraryFilter.PLAYLISTS to stringResource(R.string.filter_playlists),
                    LibraryFilter.PODCASTS to stringResource(R.string.filter_podcasts),
                    LibraryFilter.SONGS to stringResource(R.string.filter_songs),
                    LibraryFilter.ALBUMS to stringResource(R.string.filter_albums),
                    LibraryFilter.ARTISTS to stringResource(R.string.filter_artists),
                ),
                currentValue = filterType,
                onValueUpdate = {
                    filterType = if (filterType == it) LibraryFilter.LIBRARY else it
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                state = refreshState,
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (!isRefreshing) {
                        scope.launch {
                            isRefreshing = true
                            val refreshStartedAt = SystemClock.elapsedRealtime()
                            val refreshResult =
                                try {
                                    if (filterType == LibraryFilter.PODCASTS) {
                                        podcastsViewModel.refreshAll()
                                        FullSyncResult.COMPLETED
                                    } else {
                                        mixViewModel.refreshNow()
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    FullSyncResult.FAILED
                                } finally {
                                    val remainingIndicatorTime = MIN_REFRESH_INDICATOR_MS -
                                        (SystemClock.elapsedRealtime() - refreshStartedAt)
                                    if (remainingIndicatorTime > 0L) delay(remainingIndicatorTime)
                                    isRefreshing = false
                                }
                            val errorMessage =
                                when (refreshResult) {
                                    FullSyncResult.COMPLETED -> null
                                    FullSyncResult.NOT_LOGGED_IN -> refreshLoginMessage
                                    FullSyncResult.OFFLINE -> refreshOfflineMessage
                                    FullSyncResult.FAILED -> refreshFailedMessage
                                }
                            errorMessage?.let { snackbarHostState.showSnackbar(it) }
                        }
                    }
                },
            ),
    ) {
        when (filterType) {
            LibraryFilter.LIBRARY -> LibraryMixScreen(navController, filterContent, viewModel = mixViewModel)
            LibraryFilter.PLAYLISTS -> LibraryPlaylistsScreen(navController, filterContent)
            LibraryFilter.SONGS -> LibrarySongsScreen(
                navController = navController,
                onDeselect = { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.ALBUMS -> LibraryAlbumsScreen(
                navController = navController,
                onDeselect = { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.ARTISTS -> LibraryArtistsScreen(
                navController = navController,
                onDeselect = { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.PODCASTS -> LibraryPodcastsScreen(
                navController = navController,
                onDeselect = { filterType = LibraryFilter.LIBRARY },
                viewModel = podcastsViewModel,
            )
        }

        Indicator(
            isRefreshing = isRefreshing,
            state = refreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    top = LocalPlayerAwareWindowInsets.current
                        .asPaddingValues()
                        .calculateTopPadding(),
                ),
        )
    }
}

private const val MIN_REFRESH_INDICATOR_MS = 700L

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.screens.podcast.PodcastEpisodeRow
import com.metrolist.music.viewmodels.LibraryPodcastsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastCollectionScreen(
    navController: NavController,
    collection: String,
    viewModel: LibraryPodcastsViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val likedEpisodes by viewModel.savedEpisodes.collectAsStateWithLifecycle()
    val downloadedEpisodes by viewModel.downloadedEpisodes.collectAsStateWithLifecycle()
    val episodes = if (collection == COLLECTION_DOWNLOADED) downloadedEpisodes else likedEpisodes
    val title = stringResource(
        if (collection == COLLECTION_DOWNLOADED) R.string.filter_downloaded else R.string.liked,
    )
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val currentMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (episodes.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Text(
                    text = stringResource(
                        if (collection == COLLECTION_DOWNLOADED) {
                            R.string.no_downloaded_episodes
                        } else {
                            R.string.no_saved_episodes
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                itemsIndexed(
                    items = episodes,
                    key = { _, episode -> episode.id },
                ) { index, episode ->
                    PodcastEpisodeRow(
                        song = episode,
                        isActive = currentMetadata?.id == episode.id,
                        isPlaying = isPlaying,
                        onClick = {
                            if (currentMetadata?.id == episode.id) {
                                playerConnection.togglePlayPause()
                            } else {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = title,
                                        items = episodes.map { it.toMediaItem() },
                                        startIndex = index,
                                        position = episode.song.playbackPosition ?: 0L,
                                    ),
                                )
                            }
                        },
                        onMenuClick = {
                            menuState.show {
                                SongMenu(
                                    originalSong = episode,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

const val COLLECTION_LIKED = "liked"
const val COLLECTION_DOWNLOADED = "downloaded"

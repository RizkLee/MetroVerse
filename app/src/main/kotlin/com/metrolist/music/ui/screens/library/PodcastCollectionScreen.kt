package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.screens.podcast.PodcastEpisodeRow
import com.metrolist.music.utils.makeTimeString
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
    val placeholderIcon = if (collection == COLLECTION_DOWNLOADED) R.drawable.offline else R.drawable.favorite_border
    val emptyText = stringResource(
        if (collection == COLLECTION_DOWNLOADED) R.string.no_downloaded_episodes else R.string.no_saved_episodes,
    )
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val currentMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "podcast_collection_header") {
                PodcastCollectionHeader(
                    title = title,
                    icon = placeholderIcon,
                    episodes = episodes,
                    onShuffle = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = title,
                                items = episodes.shuffled().map { it.toMediaItem() },
                            ),
                        )
                    },
                    onPlay = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = title,
                                items = episodes.map { it.toMediaItem() },
                            ),
                        )
                    },
                    onAddToQueue = {
                        playerConnection.addToQueue(episodes.map { it.toMediaItem() })
                    },
                )
            }

            if (episodes.isEmpty()) {
                item(key = "empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                    ) {
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
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
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun PodcastCollectionHeader(
    title: String,
    icon: Int,
    episodes: List<Song>,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    val totalDurationSeconds = episodes.sumOf { it.song.duration.toLong() }
    val actionsEnabled = episodes.isNotEmpty()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .size(240.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(6.dp),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(96.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = buildString {
                append(pluralStringResource(R.plurals.n_episode, episodes.size, episodes.size))
                if (totalDurationSeconds > 0L) {
                    append("  ")
                    append(makeTimeString(totalDurationSeconds * 1000L))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            PodcastCollectionAction(
                icon = R.drawable.shuffle,
                contentDescription = stringResource(R.string.shuffle),
                enabled = actionsEnabled,
                primary = false,
                onClick = onShuffle,
            )
            PodcastCollectionAction(
                icon = R.drawable.play,
                contentDescription = stringResource(R.string.play),
                enabled = actionsEnabled,
                primary = true,
                onClick = onPlay,
            )
            PodcastCollectionAction(
                icon = R.drawable.playlist_add,
                contentDescription = stringResource(R.string.add_to_queue),
                enabled = actionsEnabled,
                primary = false,
                onClick = onAddToQueue,
            )
        }
    }
}

@Composable
private fun PodcastCollectionAction(
    icon: Int,
    contentDescription: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(if (primary) 72.dp else 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(if (primary) 32.dp else 24.dp),
            )
        }
    }
}

const val COLLECTION_LIKED = "liked"
const val COLLECTION_DOWNLOADED = "downloaded"

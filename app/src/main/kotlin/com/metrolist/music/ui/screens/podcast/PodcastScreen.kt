package com.metrolist.music.ui.screens.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.PodcastRegionKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.constants.SearchSourceKey
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.podcast.PodcastDiscoverItem
import com.metrolist.music.podcast.defaultPodcastRegionCode
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.PodcastHomeViewModel
import com.metrolist.music.viewmodels.PodcastUiEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: PodcastHomeViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val latestEpisodes by viewModel.latestEpisodes.collectAsStateWithLifecycle()
    val continueListening by viewModel.continueListening.collectAsStateWithLifecycle()
    val discover by viewModel.discover.collectAsStateWithLifecycle()
    val isLoadingDiscover by viewModel.isLoadingDiscover.collectAsStateWithLifecycle()
    val isLoadingMoreDiscover by viewModel.isLoadingMoreDiscover.collectAsStateWithLifecycle()
    val hasMoreDiscover by viewModel.hasMoreDiscover.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val currentMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val refreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    var showAddFeed by rememberSaveable { mutableStateOf(false) }
    var feedUrl by rememberSaveable { mutableStateOf("") }
    var podcastRegion by rememberPreference(PodcastRegionKey, defaultPodcastRegionCode())
    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val discoverColumns = when {
        screenWidth >= 840 -> 5
        screenWidth >= 600 -> 4
        else -> 2
    }

    LaunchedEffect(podcastRegion) {
        viewModel.setCountry(podcastRegion)
    }

    LaunchedEffect(listState, hasMoreDiscover) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            hasMoreDiscover && layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMoreDiscover() }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PodcastUiEvent.OpenPodcast -> navController.navigate("rss_podcast/${event.podcastId}")
                is PodcastUiEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    if (showAddFeed) {
        AlertDialog(
            onDismissRequest = { showAddFeed = false },
            title = { Text(stringResource(R.string.add_podcast_feed)) },
            text = {
                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = { feedUrl = it },
                    label = { Text(stringResource(R.string.podcast_feed_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = feedUrl.isNotBlank(),
                    onClick = {
                        viewModel.addFeed(feedUrl)
                        showAddFeed = false
                        feedUrl = ""
                    },
                ) {
                    Text(stringResource(R.string.add_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFeed = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                state = refreshState,
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
            ),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "podcast_actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Button(
                        onClick = {
                            searchSource = SearchSource.PODCAST
                            navController.navigate("search_input")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(painterResource(R.drawable.search), contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.search), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showAddFeed = true },
                        modifier = Modifier.weight(0.8f),
                    ) {
                        Icon(painterResource(R.drawable.add), contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.rss), maxLines = 1)
                    }
                    PodcastRegionSelector(
                        selectedCode = podcastRegion,
                        onSelected = { podcastRegion = it },
                        modifier = Modifier.weight(0.65f),
                    )
                }
            }

            item(key = "discover_title") {
                PodcastSectionTitle(stringResource(R.string.discover_podcasts))
            }
            if (isLoadingDiscover && discover.isEmpty()) {
                item(key = "discover_loading") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    ) {
                        ContainedLoadingIndicator()
                    }
                }
            } else {
                items(
                    items = discover.chunked(discoverColumns),
                    key = { row -> "discover_${row.firstOrNull()?.appleId.orEmpty()}" },
                ) { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        row.forEach { item ->
                            PodcastDiscoverCard(
                                item = item,
                                onClick = { viewModel.openDiscoverItem(item) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(discoverColumns - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (continueListening.isNotEmpty()) {
                item(key = "continue_title") {
                    PodcastSectionTitle(stringResource(R.string.continue_listening))
                }
                itemsIndexed(
                    items = continueListening.take(5),
                    key = { _, song -> "continue_${song.id}" },
                ) { index, song ->
                    PodcastEpisodeRow(
                        song = song,
                        isActive = currentMetadata?.id == song.id,
                        isPlaying = isPlaying,
                        onClick = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = null,
                                    items = continueListening.map(Song::toMediaItem),
                                    startIndex = index,
                                    position = song.song.playbackPosition ?: 0L,
                                ),
                            )
                        },
                        onMenuClick = {
                            menuState.show {
                                SongMenu(originalSong = song, onDismiss = menuState::dismiss)
                            }
                        },
                    )
                }
            }

            item(key = "subscriptions_title") {
                PodcastSectionTitle(stringResource(R.string.subscriptions))
            }
            if (subscriptions.isEmpty()) {
                item(key = "subscriptions_empty") {
                    PodcastEmptyLine(stringResource(R.string.no_podcast_subscriptions))
                }
            } else {
                item(key = "subscriptions") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(subscriptions, key = PodcastEntity::id) { podcast ->
                            PodcastLibraryCard(
                                podcast = podcast,
                                onClick = {
                                    navController.navigate(
                                        if (podcast.feedUrl != null) {
                                            "rss_podcast/${podcast.id}"
                                        } else {
                                            "online_podcast/${podcast.id}"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (latestEpisodes.isNotEmpty()) {
                item(key = "latest_title") {
                    PodcastSectionTitle(stringResource(R.string.latest_episodes))
                }
                itemsIndexed(
                    items = latestEpisodes.take(5),
                    key = { _, song -> "latest_${song.id}" },
                ) { index, song ->
                    PodcastEpisodeRow(
                        song = song,
                        isActive = currentMetadata?.id == song.id,
                        isPlaying = isPlaying,
                        onClick = {
                            if (currentMetadata?.id == song.id) {
                                playerConnection.togglePlayPause()
                            } else {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = null,
                                        items = latestEpisodes.take(5).map(Song::toMediaItem),
                                        startIndex = index,
                                    ),
                                )
                            }
                        },
                        onMenuClick = {
                            menuState.show {
                                SongMenu(originalSong = song, onDismiss = menuState::dismiss)
                            }
                        },
                    )
                }
            }

            if (isLoadingMoreDiscover) {
                item(key = "discover_loading_more") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        ContainedLoadingIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }
        }

        Indicator(
            isRefreshing = isRefreshing,
            state = refreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun PodcastSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun PodcastEmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
    )
}

@Composable
private fun PodcastLibraryCard(
    podcast: PodcastEntity,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .clickable(onClick = onClick),
    ) {
        PodcastArtwork(url = podcast.thumbnailUrl, modifier = Modifier.size(124.dp))
        Text(
            text = podcast.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        podcast.author?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PodcastDiscoverCard(
    item: PodcastDiscoverItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        PodcastArtwork(
            url = item.artworkUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = item.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PodcastArtwork(
    url: String?,
    modifier: Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                painter = painterResource(R.drawable.podcast),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun PodcastEpisodeRow(
    song: Song,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = song.song.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).orEmpty()
    val subtitle = joinByBullet(
        song.song.albumName.orEmpty(),
        date,
        song.song.duration.takeIf { it > 0 }?.let { makeTimeString(it * 1000L) }.orEmpty(),
    )
    SongListItem(
        song = song,
        showLikedIcon = false,
        showInLibraryIcon = true,
        showDownloadIcon = true,
        subtitleOverride = subtitle.ifBlank { null },
        isActive = isActive,
        isPlaying = isPlaying,
        trailingContent = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = stringResource(R.string.more_options),
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

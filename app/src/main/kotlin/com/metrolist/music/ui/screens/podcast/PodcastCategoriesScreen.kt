package com.metrolist.music.ui.screens.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.podcast.PodcastCategory
import com.metrolist.music.ui.screens.MoodAndGenresButton
import com.metrolist.music.viewmodels.PodcastCategoryViewModel
import com.metrolist.music.viewmodels.PodcastUiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastCategoriesScreen(
    navController: NavController,
    country: String,
) {
    val itemsPerRow = if (LocalWindowInfo.current.containerDpSize.width >= 600.dp) 3 else 2

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.podcast_categories)) },
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
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            PodcastCategory.entries.chunked(itemsPerRow).forEachIndexed { index, row ->
                item(key = "podcast_category_row_$index") {
                    Row(modifier = Modifier.padding(horizontal = 10.dp)) {
                        row.forEach { category ->
                            MoodAndGenresButton(
                                title = stringResource(category.titleRes),
                                onClick = {
                                    navController.navigate("podcast_category/${category.slug}/$country")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(6.dp),
                            )
                        }
                        repeat(itemsPerRow - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastCategoryScreen(
    navController: NavController,
    snackbarHostState: SnackbarHostState,
    viewModel: PodcastCategoryViewModel = hiltViewModel(),
) {
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val refreshState = rememberPullToRefreshState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PodcastUiEvent.OpenPodcast -> navController.navigate("rss_podcast/${event.podcastId}")
                is PodcastUiEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(viewModel.category.titleRes)) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .pullToRefresh(
                    state = refreshState,
                    isRefreshing = isLoading,
                    onRefresh = viewModel::refresh,
                ),
        ) {
            if (podcasts.isEmpty() && !isLoading) {
                Text(
                    text = stringResource(R.string.no_podcast_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(podcasts, key = { it.appleId }) { podcast ->
                        PodcastDiscoverCard(
                            item = podcast,
                            onClick = { viewModel.open(podcast) },
                        )
                    }
                }
            }

            if (isLoading && podcasts.isEmpty()) {
                ContainedLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }
            Indicator(
                isRefreshing = isLoading,
                state = refreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

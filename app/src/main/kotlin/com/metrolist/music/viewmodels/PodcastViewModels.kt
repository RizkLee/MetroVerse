package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.podcast.PodcastCategory
import com.metrolist.music.podcast.PodcastDiscoverItem
import com.metrolist.music.podcast.PodcastRepository
import com.metrolist.music.podcast.defaultPodcastRegionCode
import com.metrolist.music.podcast.normalizePodcastRegionCode
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PodcastUiEvent {
    data class OpenPodcast(val podcastId: String) : PodcastUiEvent
    data class Error(val message: String) : PodcastUiEvent
}

@HiltViewModel
class PodcastHomeViewModel @Inject constructor(
    private val database: MusicDatabase,
    private val repository: PodcastRepository,
) : ViewModel() {
    val subscriptions = database.subscribedPodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latestEpisodes = database.latestRssPodcastEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val continueListening = database.continueRssPodcastEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _discover = MutableStateFlow<List<PodcastDiscoverItem>>(emptyList())
    val discover = _discover.asStateFlow()
    private val _isLoadingDiscover = MutableStateFlow(false)
    val isLoadingDiscover = _isLoadingDiscover.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _events = MutableSharedFlow<PodcastUiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    private var country = defaultPodcastRegionCode()
    private var discoverJob: Job? = null
    private var refreshJob: Job? = null
    private var discoverGeneration = 0

    init {
        refresh()
    }

    fun setCountry(value: String) {
        val normalized = normalizePodcastRegionCode(value)
        if (normalized == country) {
            if (_discover.value.isEmpty() && !_isRefreshing.value && !_isLoadingDiscover.value) {
                loadDiscover()
            }
            return
        }
        country = normalized
        _discover.value = emptyList()
        loadDiscover()
    }

    private fun loadDiscover() {
        val generation = ++discoverGeneration
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoadingDiscover.value = true
            try {
                val items = repository.topPodcasts(country = country, limit = DISCOVER_ITEM_COUNT)
                if (generation == discoverGeneration) {
                    _discover.value = items
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == discoverGeneration) {
                    _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast discovery failed"))
                }
            } finally {
                if (generation == discoverGeneration) {
                    _isLoadingDiscover.value = false
                }
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        val generation = ++discoverGeneration
        discoverJob?.cancel()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            try {
                val failures = repository.refreshSubscribed()
                    .mapNotNull(Result<*>::exceptionOrNull)
                    .toMutableList()
                try {
                    val items = repository.topPodcasts(country = country, limit = DISCOVER_ITEM_COUNT)
                    if (generation == discoverGeneration) {
                        _discover.value = items.shuffled()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += error
                }
                failures.firstOrNull()?.let { error ->
                    if (generation == discoverGeneration) {
                        _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast refresh failed"))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun openDiscoverItem(item: PodcastDiscoverItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val podcast = repository.importPodcast(item)
                _events.emit(PodcastUiEvent.OpenPodcast(podcast.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast feed failed"))
            }
        }
    }

    fun addFeed(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val podcast = repository.fetchFeed(url, subscribe = true)
                _events.emit(PodcastUiEvent.OpenPodcast(podcast.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast feed failed"))
            }
        }
    }

    private companion object {
        const val DISCOVER_ITEM_COUNT = 50
    }
}

@HiltViewModel
class PodcastCategoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
) : ViewModel() {
    val category = requireNotNull(PodcastCategory.fromSlug(savedStateHandle.get<String>("category")))
    private val country = normalizePodcastRegionCode(
        savedStateHandle.get<String>("country") ?: defaultPodcastRegionCode(),
    )

    private val _podcasts = MutableStateFlow<List<PodcastDiscoverItem>>(emptyList())
    val podcasts = _podcasts.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _events = MutableSharedFlow<PodcastUiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_isLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                _podcasts.value = repository.topPodcasts(
                    country = country,
                    limit = CATEGORY_ITEM_COUNT,
                    genreId = category.appleGenreId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast category failed"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun open(item: PodcastDiscoverItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val podcast = repository.importPodcast(item)
                _events.emit(PodcastUiEvent.OpenPodcast(podcast.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast feed failed"))
            }
        }
    }

    private companion object {
        const val CATEGORY_ITEM_COUNT = 50
    }
}

@HiltViewModel
class PodcastSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val repository: PodcastRepository,
) : ViewModel() {
    private val initialQuery = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    private val _query = MutableStateFlow(initialQuery)
    val query = _query.asStateFlow()
    private val _results = MutableStateFlow<List<PodcastDiscoverItem>>(emptyList())
    val results = _results.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _isOpening = MutableStateFlow(false)
    val isOpening = _isOpening.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _events = MutableSharedFlow<PodcastUiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    private var country = defaultPodcastRegionCode()
    private var searchJob: Job? = null
    private var searchGeneration = 0

    init {
        if (!isFeedQuery(initialQuery)) search()
    }

    fun setCountry(value: String) {
        val normalized = normalizePodcastRegionCode(value)
        if (normalized == country && (_results.value.isNotEmpty() || _isLoading.value)) return
        country = normalized
        if (!isFeedQuery(_query.value)) search()
    }

    fun updateQuery(value: String) {
        _query.value = value
        if (value.isBlank()) {
            searchJob?.cancel()
            _results.value = emptyList()
            _error.value = null
            _isLoading.value = false
        }
    }

    fun search(value: String = _query.value) {
        val normalizedQuery = value.trim()
        if (normalizedQuery.isEmpty()) return
        _query.value = normalizedQuery
        if (isFeedQuery(normalizedQuery)) {
            searchJob?.cancel()
            _results.value = emptyList()
            _error.value = null
            _isLoading.value = false
            saveHistory(normalizedQuery)
            return
        }

        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val items = repository.search(normalizedQuery, country = country)
                if (generation == searchGeneration) _results.value = items
                saveHistory(normalizedQuery)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == searchGeneration) {
                    _error.value = error.message ?: "Podcast search failed"
                }
            } finally {
                if (generation == searchGeneration) _isLoading.value = false
            }
        }
    }

    fun open(item: PodcastDiscoverItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _isOpening.value = true
            try {
                val podcast = repository.importPodcast(item)
                _events.emit(PodcastUiEvent.OpenPodcast(podcast.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast feed failed"))
            } finally {
                _isOpening.value = false
            }
        }
    }

    fun openFeed() {
        val feedQuery = _query.value.trim()
        if (!isFeedQuery(feedQuery)) return
        viewModelScope.launch(Dispatchers.IO) {
            _isOpening.value = true
            try {
                saveHistory(feedQuery)
                val podcast = repository.fetchFeed(feedQuery)
                _events.emit(PodcastUiEvent.OpenPodcast(podcast.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _events.emit(PodcastUiEvent.Error(error.message ?: "Podcast feed failed"))
            } finally {
                _isOpening.value = false
            }
        }
    }

    private fun saveHistory(value: String) {
        if (context.dataStore.get(PauseSearchHistoryKey, false)) return
        database.query {
            insert(SearchHistory(query = value, source = SearchSource.PODCAST.name))
        }
    }

    companion object {
        fun isFeedQuery(value: String): Boolean {
            val trimmed = value.trim()
            return trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        }
    }
}

@HiltViewModel
class RssPodcastViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: MusicDatabase,
    private val repository: PodcastRepository,
) : ViewModel() {
    private val podcastId = requireNotNull(savedStateHandle.get<String>("podcastId"))
    val podcast = database.podcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val episodes = database.rssPodcastEpisodes(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun refresh() {
        val feedUrl = podcast.value?.feedUrl ?: return
        if (_isRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            _error.value = null
            try {
                repository.fetchFeed(feedUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _error.value = error.message ?: "Podcast refresh failed"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleSubscription() {
        val current = podcast.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.setSubscribed(current.id, current.bookmarkedAt == null)
        }
    }
}

package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.podcast.PodcastDiscoverItem
import com.metrolist.music.podcast.PodcastRepository
import com.metrolist.music.utils.SearchRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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

    init {
        loadDiscover()
    }

    fun loadDiscover() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingDiscover.value = true
            runCatching { repository.topPodcasts() }
                .onSuccess { _discover.value = it }
                .onFailure { _events.emit(PodcastUiEvent.Error(it.message ?: "Podcast discovery failed")) }
            _isLoadingDiscover.value = false
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            val failures = repository.refreshSubscribed()
                .mapNotNull(Result<*>::exceptionOrNull)
                .toMutableList()
            runCatching { repository.topPodcasts() }
                .onSuccess { _discover.value = it }
                .onFailure(failures::add)
            failures.firstOrNull()?.let {
                _events.emit(PodcastUiEvent.Error(it.message ?: "Podcast refresh failed"))
            }
            _isRefreshing.value = false
        }
    }

    fun openDiscoverItem(item: PodcastDiscoverItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.importPodcast(item) }
                .onSuccess { _events.emit(PodcastUiEvent.OpenPodcast(it.id)) }
                .onFailure { _events.emit(PodcastUiEvent.Error(it.message ?: "Podcast feed failed")) }
        }
    }

    fun addFeed(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.fetchFeed(url, subscribe = true) }
                .onSuccess { _events.emit(PodcastUiEvent.OpenPodcast(it.id)) }
                .onFailure { _events.emit(PodcastUiEvent.Error(it.message ?: "Podcast feed failed")) }
        }
    }
}

@HiltViewModel
class PodcastSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
) : ViewModel() {
    val query = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    val looksLikeFeed = query.trim().startsWith("http://", true) || query.trim().startsWith("https://", true)

    private val _results = MutableStateFlow<List<PodcastDiscoverItem>>(emptyList())
    val results = _results.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _events = MutableSharedFlow<PodcastUiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    init {
        if (!looksLikeFeed) search()
    }

    fun search() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            runCatching { repository.search(query) }
                .onSuccess { _results.value = it }
                .onFailure { _error.value = it.message ?: "Podcast search failed" }
            _isLoading.value = false
        }
    }

    fun open(item: PodcastDiscoverItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            runCatching { repository.importPodcast(item) }
                .onSuccess { _events.emit(PodcastUiEvent.OpenPodcast(it.id)) }
                .onFailure { _events.emit(PodcastUiEvent.Error(it.message ?: "Podcast feed failed")) }
            _isLoading.value = false
        }
    }

    fun openFeed() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            runCatching { repository.fetchFeed(query) }
                .onSuccess { _events.emit(PodcastUiEvent.OpenPodcast(it.id)) }
                .onFailure { _events.emit(PodcastUiEvent.Error(it.message ?: "Podcast feed failed")) }
            _isLoading.value = false
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
            runCatching { repository.fetchFeed(feedUrl) }
                .onFailure { _error.value = it.message ?: "Podcast refresh failed" }
            _isRefreshing.value = false
        }
    }

    fun toggleSubscription() {
        val current = podcast.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.setSubscribed(current.id, current.bookmarkedAt == null)
        }
    }
}

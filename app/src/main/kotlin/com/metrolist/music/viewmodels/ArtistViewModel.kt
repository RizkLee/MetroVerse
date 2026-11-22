/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.music.artwork.AnimatedArtworkService
import com.metrolist.music.artwork.ArtistArtworkFetchResult
import com.metrolist.music.artwork.isAnimatedArtworkAllowedOnNetwork
import com.metrolist.music.artwork.isEligibleForAppleMusicArtist
import com.metrolist.music.constants.AnimatedCoverResolution
import com.metrolist.music.constants.AppLanguageKey
import com.metrolist.music.constants.AppleMusicArtistVideoResolutionKey
import com.metrolist.music.constants.ContentCountryKey
import com.metrolist.music.constants.ContentLanguageKey
import com.metrolist.music.constants.DisableAnimatedArtworkOnMobileDataKey
import com.metrolist.music.constants.EnableAppleMusicArtistImagesKey
import com.metrolist.music.constants.EnableAppleMusicArtistVideosKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.deserializeArtistPage
import com.metrolist.music.db.entities.serializeArtistPage
import com.metrolist.music.db.entities.toArtistPage
import com.metrolist.music.utils.ContentFilter
import com.metrolist.music.utils.ContentFilterSnapshot
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

data class AppleMusicArtistArtworkState(
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val isImageLookupComplete: Boolean = false,
)

private data class ArtistArtworkPrefsSnapshot(
    val includeImage: Boolean,
    val includeVideo: Boolean,
    val disableOnMobileData: Boolean,
    val videoResolution: AnimatedCoverResolution,
    val country: String?,
    val language: String?,
    val appLanguage: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
    private val animatedArtworkService: AnimatedArtworkService,
    private val networkConnectivityObserver: NetworkConnectivityObserver,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    private val isPodcastChannel = savedStateHandle.get<Boolean>("isPodcastChannel") ?: false
    var artistPage by mutableStateOf<ArtistPage?>(null)
    private var unfilteredPage: ArtistPage? = null
    private var hasFetchedFromYtm = false

    // Track API subscription state separately
    private val _apiSubscribed = MutableStateFlow<Boolean?>(null)
    private val resolvedArtistName = MutableStateFlow<String?>(null)
    private val _appleMusicArtwork = MutableStateFlow(AppleMusicArtistArtworkState())
    val appleMusicArtwork: StateFlow<AppleMusicArtistArtworkState> = _appleMusicArtwork.asStateFlow()
    private var lastArtworkRequestKey: String? = null
    private var artworkFetchJob: Job? = null

    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Combine API state with local database state - local takes precedence when not logged in
    val isChannelSubscribed = kotlinx.coroutines.flow.combine(
        _apiSubscribed,
        database.artist(artistId),
    ) { apiState, localArtist ->
        val locallyBookmarked = localArtist?.artist?.bookmarkedAt != null
        locallyBookmarked || (apiState == true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val librarySongs = ContentFilter.observe(context)
        .flatMapLatest { filter ->
            database.artistSongsPreview(artistId).map(filter::filterSongs)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = ContentFilter.observe(context)
        .flatMapLatest { filter ->
            database.artistAlbumsPreview(artistId).map(filter::filterAlbums)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            // Load cached page first for instant display, then fetch fresh data
            loadCachedPage()

            ContentFilter.observe(context)
                .collect { filter ->
                    if (!hasFetchedFromYtm) {
                        hasFetchedFromYtm = true
                        fetchArtistsFromYTM()
                    } else {
                        unfilteredPage?.let { page ->
                            artistPage = applyPageFilter(page, filter)
                        }
                    }
                }
        }

        observeAppleMusicArtwork()
    }

    private fun observeAppleMusicArtwork() {
        val prefsFlow =
            context.dataStore.data
                .map { prefs ->
                    ArtistArtworkPrefsSnapshot(
                        includeImage = prefs[EnableAppleMusicArtistImagesKey] ?: false,
                        includeVideo = prefs[EnableAppleMusicArtistVideosKey] ?: false,
                        disableOnMobileData = prefs[DisableAnimatedArtworkOnMobileDataKey] ?: false,
                        videoResolution = AnimatedCoverResolution.fromStoredValue(prefs[AppleMusicArtistVideoResolutionKey]),
                        country = prefs[ContentCountryKey],
                        language = prefs[ContentLanguageKey],
                        appLanguage = prefs[AppLanguageKey],
                    )
                }
                .distinctUntilChanged()

        val localArtistMetaFlow =
            libraryArtist
                .map { Triple(it?.artist?.name, it?.artist?.isLocal == true, it?.artist?.isPodcastChannel == true) }
                .distinctUntilChanged()

        viewModelScope.launch {
            combine(
                resolvedArtistName,
                localArtistMetaFlow,
                prefsFlow,
                networkConnectivityObserver.isCellular,
            ) { apiName, (localName, isLocalArtist, dbPodcastChannel), prefs, isCellular ->
                val effectiveName = apiName?.takeIf(String::isNotBlank) ?: localName?.takeIf(String::isNotBlank)
                Triple(effectiveName, isLocalArtist to (isPodcastChannel || dbPodcastChannel), prefs to isCellular)
            }.collectLatest { (effectiveName, flags, networkPrefs) ->
                val (isLocalArtist, podcastArtist) = flags
                val (prefs, isCellular) = networkPrefs
                val eligible =
                    isEligibleForAppleMusicArtist(
                        artistId = artistId,
                        artistName = effectiveName,
                        isPodcastChannel = podcastArtist,
                        isLocalArtist = isLocalArtist,
                    )
                val includeImage = eligible && prefs.includeImage
                val includeVideo =
                    eligible &&
                        prefs.includeVideo &&
                        isAnimatedArtworkAllowedOnNetwork(prefs.disableOnMobileData, isCellular)

                if (!eligible || (!includeImage && !includeVideo) || effectiveName == null) {
                    artworkFetchJob?.cancel()
                    lastArtworkRequestKey = null
                    _appleMusicArtwork.value = AppleMusicArtistArtworkState(isImageLookupComplete = true)
                    return@collectLatest
                }

                val requestKey =
                    listOf(
                        artistId,
                        effectiveName,
                        includeImage,
                        includeVideo,
                        prefs.videoResolution.name,
                        prefs.country,
                        prefs.language,
                        prefs.appLanguage,
                    ).joinToString("|")
                if (requestKey == lastArtworkRequestKey) return@collectLatest
                lastArtworkRequestKey = requestKey
                artworkFetchJob?.cancel()
                _appleMusicArtwork.value =
                    AppleMusicArtistArtworkState(
                        imageUrl = _appleMusicArtwork.value.imageUrl.takeIf { includeImage },
                        videoUrl = null,
                        isImageLookupComplete = !includeImage || _appleMusicArtwork.value.imageUrl != null,
                    )

                artworkFetchJob =
                    launch {
                        try {
                            when (
                                val result =
                                    animatedArtworkService.fetchArtistArtwork(
                                        artist = effectiveName,
                                        includeImage = includeImage,
                                        includeVideo = includeVideo,
                                        videoResolution = prefs.videoResolution,
                                    )
                            ) {
                                is ArtistArtworkFetchResult.Found -> {
                                    if (lastArtworkRequestKey == requestKey) {
                                        _appleMusicArtwork.value =
                                            AppleMusicArtistArtworkState(
                                                imageUrl = result.artwork.imageUrl,
                                                videoUrl = result.artwork.videoUrl,
                                                isImageLookupComplete = true,
                                            )
                                    }
                                }

                                ArtistArtworkFetchResult.Missing -> {
                                    if (lastArtworkRequestKey == requestKey) {
                                        _appleMusicArtwork.value =
                                            AppleMusicArtistArtworkState(isImageLookupComplete = true)
                                    }
                                }

                                ArtistArtworkFetchResult.Unavailable -> {
                                    if (lastArtworkRequestKey == requestKey) {
                                        lastArtworkRequestKey = null
                                        _appleMusicArtwork.value =
                                            _appleMusicArtwork.value.copy(isImageLookupComplete = true)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            if (lastArtworkRequestKey == requestKey) {
                                lastArtworkRequestKey = null
                                _appleMusicArtwork.value =
                                    _appleMusicArtwork.value.copy(isImageLookupComplete = true)
                            }
                        }
                    }
            }
        }
    }

    private fun applyPageFilter(
        page: ArtistPage,
        filter: ContentFilterSnapshot,
    ): ArtistPage {
        val filteredSections = page.sections
            .map { section ->
                section.copy(items = filter.filterItems(section.items))
            }
            .filter { section -> section.items.isNotEmpty() }
        return page.copy(sections = filteredSections)
    }

    private suspend fun loadCachedPage() {
        try {
            val cachedJson = database.artist(artistId).firstOrNull()?.artist?.cachedPageJson
            if (cachedJson != null) {
                val cachedDto = withContext(Dispatchers.IO) { deserializeArtistPage(cachedJson) }
                val page = cachedDto.toArtistPage()
                unfilteredPage = page
                val filter = ContentFilter.current(context)
                artistPage = applyPageFilter(page, filter)
                resolvedArtistName.value = page.artist.title.takeIf(String::isNotBlank)
                _apiSubscribed.value = page.isSubscribed
            }
        } catch (e: Exception) {
            reportException(e)
        }
    }

    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            YouTube.artist(artistId)
                .onSuccess { page ->
                    // Collect all items from all sections and resolve artist IDs once
                    val allItems = page.sections.flatMap { it.items }
                    val resolvedIdMap = if (allItems.isNotEmpty()) {
                        YouTube.resolveArtistIdMap(allItems)
                    } else {
                        emptyMap()
                    }

                    fun com.metrolist.innertube.models.Artist.resolve() =
                        if (id == null) resolvedIdMap[name]?.let { copy(id = it) } ?: this else this

                    // Resolve artist IDs and fetch durations from more endpoint
                    val resolvedSections = page.sections.map { section ->
                        section.copy(items = section.items.map { item ->
                            when (item) {
                                is SongItem -> item.copy(artists = item.artists.map { it.resolve() })
                                is AlbumItem -> item.copy(artists = item.artists?.map { it.resolve() })
                                is PlaylistItem -> item.copy(author = item.author?.resolve())
                                is EpisodeItem -> item.copy(author = item.author?.resolve())
                                is PodcastItem -> item.copy(author = item.author?.resolve())
                                else -> item
                            }
                        })
                    }

                    // Fetch song durations from the more endpoint if the first section has songs without duration
                    var sectionsWithDurations = resolvedSections
                    if (resolvedSections.isNotEmpty()) {
                        val needDurations = resolvedSections.first().items.any {
                            it is SongItem && it.duration == null
                        }
                        if (needDurations) {
                            val moreEndpoint = resolvedSections.first().moreEndpoint
                            if (moreEndpoint != null) {
                                try {
                                    val moreResult = withContext(Dispatchers.IO) {
                                        YouTube.artistItems(moreEndpoint)
                                    }
                                    moreResult
                                        .onSuccess { moreItems ->
                                            val durationById = moreItems.items.filterIsInstance<SongItem>()
                                                .associate { it.id to it.duration }
                                            if (durationById.isNotEmpty()) {
                                                sectionsWithDurations = listOf(resolvedSections.first().copy(
                                                    items = resolvedSections.first().items.map { item ->
                                                        if (item is SongItem && item.duration == null) {
                                                            item.copy(duration = durationById[item.id] ?: item.duration)
                                                        } else item
                                                    }
                                                )) + resolvedSections.drop(1)
                                            }
                                        }
                                        .onFailure { e -> reportException(e) }
                                } catch (e: Exception) {
                                    reportException(e)
                                }
                            }
                        }
                    }

                    val resolvedPage = page.copy(sections = sectionsWithDurations)
                    unfilteredPage = resolvedPage
                    artistPage = applyPageFilter(resolvedPage, ContentFilter.current(context))
                    resolvedArtistName.value = resolvedPage.artist.title.takeIf(String::isNotBlank)
                    // Cache page data + persist artist metadata
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val cachedJson = serializeArtistPage(
                                sections = resolvedPage.sections,
                                description = resolvedPage.description,
                                subscriberCountText = resolvedPage.subscriberCountText,
                                monthlyListenerCount = resolvedPage.monthlyListenerCount,
                                isSubscribed = resolvedPage.isSubscribed,
                                artist = resolvedPage.artist,
                            )
                            val existingArtist = database.artist(artistId).firstOrNull()?.artist
                            if (existingArtist != null) {
                                database.update(
                                    existingArtist.copy(
                                        name = resolvedPage.artist.title,
                                        channelId = resolvedPage.artist.channelId ?: existingArtist.channelId,
                                        thumbnailUrl = resolvedPage.artist.thumbnail ?: existingArtist.thumbnailUrl,
                                        cachedPageJson = cachedJson,
                                        lastUpdateTime = java.time.LocalDateTime.now(),
                                    )
                                )
                            } else {
                                val apiArtist = resolvedPage.artist
                                database.insert(
                                    ArtistEntity(
                                        id = artistId,
                                        name = apiArtist.title,
                                        channelId = apiArtist.channelId,
                                        thumbnailUrl = apiArtist.thumbnail,
                                        cachedPageJson = cachedJson,
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            reportException(e)
                        }
                    }
                    // Store API subscription state
                    _apiSubscribed.value = resolvedPage.isSubscribed
                }.onFailure {
                    reportException(it)
                }
        }
    }

    fun toggleChannelSubscription() {
        val channelId = artistPage?.artist?.channelId ?: artistId
        val isCurrentlySubscribed = isChannelSubscribed.value
        val shouldBeSubscribed = !isCurrentlySubscribed

        Timber.d("[CHANNEL_TOGGLE] toggleChannelSubscription called: artistId=$artistId, channelId=$channelId, isCurrentlySubscribed=$isCurrentlySubscribed, shouldBeSubscribed=$shouldBeSubscribed")

        // Optimistically update API state for immediate UI feedback
        _apiSubscribed.value = shouldBeSubscribed

        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("[CHANNEL_TOGGLE] Inside coroutine, updating database...")
            // Update local database first (optimistic update)
            // Call DAO methods directly - they're synchronous on IO dispatcher
            val artist = libraryArtist.value?.artist
            Timber.d("[CHANNEL_TOGGLE] libraryArtist.value?.artist = $artist")
            if (artist != null) {
                val newBookmark = if (shouldBeSubscribed) {
                    artist.bookmarkedAt ?: java.time.LocalDateTime.now()
                } else {
                    null
                }
                // Also set isPodcastChannel if subscribing from podcast context
                val updatedArtist = artist.copy(
                    bookmarkedAt = newBookmark,
                    isPodcastChannel = if (shouldBeSubscribed && isPodcastChannel) true else artist.isPodcastChannel
                )
                Timber.d("[CHANNEL_TOGGLE] Updating existing artist: ${artist.id} -> bookmarkedAt=$newBookmark, isPodcastChannel=${updatedArtist.isPodcastChannel}")
                database.update(updatedArtist)
            } else if (shouldBeSubscribed) {
                Timber.d("[CHANNEL_TOGGLE] No existing artist, inserting new one")
                artistPage?.artist?.let {
                    database.insert(
                        ArtistEntity(
                            id = artistId,
                            name = it.title,
                            channelId = it.channelId,
                            thumbnailUrl = it.thumbnail,
                            bookmarkedAt = java.time.LocalDateTime.now(),
                            isPodcastChannel = isPodcastChannel,
                        )
                    )
                    Timber.d("[CHANNEL_TOGGLE] Inserted new artist: $artistId, isPodcastChannel=$isPodcastChannel")
                } ?: Timber.d("[CHANNEL_TOGGLE] artistPage?.artist is null, cannot insert")
            } else {
                Timber.d("[CHANNEL_TOGGLE] No artist and shouldBeSubscribed=false, nothing to do")
            }

            Timber.d("[CHANNEL_TOGGLE] Calling syncUtils.subscribeChannel($channelId, $shouldBeSubscribed)")
            // Sync with YouTube (handles login check internally)
            syncUtils.subscribeChannel(channelId, shouldBeSubscribed)
        }
    }
}

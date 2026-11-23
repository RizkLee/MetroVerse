/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.artwork

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.AnimatedCoverResolution
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class AnimatedArtworkService internal constructor(
    cacheDir: File,
    private val httpClientProvider: () -> OkHttpClient,
    private val localeProvider: () -> Pair<String, String>,
    private val endpoints: Endpoints = Endpoints.DEFAULT,
    private val urlTrustCheck: (String) -> Boolean = ::isTrustedArtworkUrl,
    private val timeSecondsProvider: () -> Long = { System.currentTimeMillis() / 1000L },
    cacheLoadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        cacheDir = File(context.cacheDir, CACHE_SUBDIR),
        httpClientProvider = { ArtworkNetworkClient.current() },
        localeProvider = {
            val country = YouTube.locale.gl.lowercase().ifBlank { "us" }
            val language = YouTube.locale.hl.lowercase()
            country to language
        },
    )

    internal data class Endpoints(
        val itunesBaseUrl: String,
        val appleMusicWebBaseUrl: String,
        val ampApiBaseUrl: String,
    ) {
        companion object {
            val DEFAULT =
                Endpoints(
                    itunesBaseUrl = "https://itunes.apple.com",
                    appleMusicWebBaseUrl = "https://music.apple.com",
                    ampApiBaseUrl = "https://amp-api.music.apple.com",
                )
        }
    }

    private val coverCacheFile = File(cacheDir, "covers.json")
    private val artistCacheFile = File(cacheDir, "artists_v2.json")
    private val cache = mutableMapOf<String, CacheEntry>()
    private val artistCache = mutableMapOf<String, ArtistCacheEntry>()
    private val cacheMutex = Mutex()
    private val tokenMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + cacheLoadDispatcher)
    private var saveJob: Job? = null
    private var artistSaveJob: Job? = null
    private var cacheGeneration = 0L
    private var tokenCache: TokenCache? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val artistCacheSerializer = MapSerializer(String.serializer(), ArtistCacheEntry.serializer())

    private val cacheLoadJob =
        scope.launch {
            loadCacheFromDisk()
            loadArtistCacheFromDisk()
        }

    internal fun dispose() {
        saveJob?.cancel()
        artistSaveJob?.cancel()
    }

    suspend fun fetchArtistArtwork(
        artist: String,
        includeImage: Boolean,
        includeVideo: Boolean,
        videoResolution: AnimatedCoverResolution,
    ): ArtistArtworkFetchResult {
        if (!includeImage && !includeVideo) return ArtistArtworkFetchResult.Unavailable
        if (artist.isBlank() || artist.length > MAX_METADATA_CHARS) return ArtistArtworkFetchResult.Missing

        cacheLoadJob.join()

        val (rawCountry, rawLanguage) = localeProvider()
        val country = rawCountry.lowercase().takeIf(String::isNotBlank) ?: return ArtistArtworkFetchResult.Unavailable
        val language = rawLanguage.lowercase().takeIf(String::isNotBlank).orEmpty()
        val cacheKey = "$country|$language|${normalizeArtistProfileName(artist)}|$includeImage|$includeVideo|${videoResolution.name}"

        val generation =
            cacheMutex.withLock {
                artistCache[cacheKey]?.let { entry ->
                    val maxAge = if (entry.shortLived) ARTIST_NEGATIVE_CACHE_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS
                    if (timeSecondsProvider() - entry.cachedAt <= maxAge) {
                        val validImage = entry.imageUrl?.takeIf(urlTrustCheck)
                        val validVideo = entry.videoUrl?.takeIf(urlTrustCheck)
                        return if (entry.isMissing || (validImage == null && validVideo == null)) {
                            ArtistArtworkFetchResult.Missing
                        } else {
                            ArtistArtworkFetchResult.Found(ArtistArtworkResult(validImage, validVideo))
                        }
                    }
                    artistCache.remove(cacheKey)
                }
                cacheGeneration
            }

        val result =
            try {
                val candidateIds =
                    withContext(Dispatchers.IO) {
                        resolveArtistCandidates(
                            artistName = artist,
                            preserveFullName = true,
                            country = country,
                            language = language,
                            propagateFailures = true,
                        )
                    }
                Timber.tag(TAG).d("Artist lookup '$artist' ($country) candidates=$candidateIds")
                if (candidateIds.isEmpty()) {
                    null
                } else {
                    val token = withContext(Dispatchers.IO) { getDevToken(country) } ?: return ArtistArtworkFetchResult.Unavailable
                    var bestResult: ArtistArtworkResult? = null
                    for (candidateId in candidateIds) {
                        val candidateResult =
                            withContext(Dispatchers.IO) {
                                fetchArtistAssets(candidateId, token, country, language, includeImage, includeVideo, videoResolution)
                            } ?: continue
                        if (bestResult == null) {
                            bestResult = candidateResult
                        }
                        val hasRequestedVideo = !includeVideo || candidateResult.videoUrl != null
                        val hasRequestedImage = !includeImage || candidateResult.imageUrl != null
                        if (hasRequestedVideo && hasRequestedImage) {
                            bestResult = candidateResult
                            break
                        } else if (includeVideo && bestResult.videoUrl == null && candidateResult.videoUrl != null) {
                            bestResult =
                                ArtistArtworkResult(
                                    imageUrl = candidateResult.imageUrl ?: bestResult.imageUrl,
                                    videoUrl = candidateResult.videoUrl,
                                )
                            if (hasRequestedImage) break
                        }
                    }
                    bestResult
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Timber.tag(TAG).w("Artist artwork fetch failed (${e::class.simpleName})")
                return ArtistArtworkFetchResult.Unavailable
            }
        Timber.tag(TAG).d("Artist lookup '$artist' result image=${result?.imageUrl != null} video=${result?.videoUrl != null}")

        val shouldSave =
            cacheMutex.withLock {
                if (cacheGeneration != generation) return@withLock false
                artistCache[cacheKey] =
                    ArtistCacheEntry(
                        imageUrl = result?.imageUrl,
                        videoUrl = result?.videoUrl,
                        cachedAt = timeSecondsProvider(),
                        isMissing = result == null,
                        shortLived = result == null || (includeVideo && result.videoUrl == null),
                    )
                while (artistCache.size > MAX_ARTIST_CACHE_ENTRIES) {
                    artistCache.minByOrNull { it.value.cachedAt }?.key?.let(artistCache::remove)
                }
                true
            }
        if (shouldSave) scheduleArtistCacheSave()
        return result?.let(ArtistArtworkFetchResult::Found) ?: ArtistArtworkFetchResult.Missing
    }

    suspend fun fetchAnimatedCover(
        title: String,
        artist: String,
        album: String? = null,
        explicit: Boolean? = null,
        resolution: AnimatedCoverResolution = AnimatedCoverResolution.MEDIUM_720,
    ): AnimatedCoverResult? {
        if (
            title.isBlank() ||
            artist.isBlank() ||
            title.length > MAX_METADATA_CHARS ||
            artist.length > MAX_METADATA_CHARS ||
            (album?.length ?: 0) > MAX_METADATA_CHARS
        ) {
            return null
        }

        cacheLoadJob.join()

        val (rawCountry, rawLanguage) = localeProvider()
        val country = rawCountry.lowercase().ifBlank { "us" }
        val language = rawLanguage.lowercase().takeIf(String::isNotBlank)
        val artworkExplicitness = resolveArtworkExplicitness(album, explicit)
        val cacheKey = buildCacheKey(title, artist, album, artworkExplicitness, resolution, country)
        val now = timeSecondsProvider()

        val generation =
            cacheMutex.withLock {
                val entry = cache[cacheKey]
                if (entry != null) {
                    val maxAge = if (entry.url == null) CACHE_MISS_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS
                    if (now - entry.cachedAt > maxAge) {
                        cache.remove(cacheKey)
                    } else {
                        val url = entry.url
                        if (url != null && urlTrustCheck(url)) {
                            Timber.tag(TAG).d("Animated artwork cache hit")
                            return AnimatedCoverResult(url = url, tallUrl = entry.tallUrl?.takeIf(urlTrustCheck))
                        }
                        if (url != null) {
                            Timber.tag(TAG).w("Refusing to serve untrusted cached animated artwork URL")
                        }
                        return null
                    }
                }
                cacheGeneration
            }

        suspend fun cacheResult(
            value: String?,
            tallValue: String? = null,
        ) = cacheAndSave(cacheKey, value, tallValue, generation)

        return try {
            val collectionResolution =
                withContext(Dispatchers.IO) {
                    resolveCollectionIds(title, artist, album, artworkExplicitness, country)
                }
            val collectionIds = collectionResolution.collectionIds
            Timber.tag(TAG).d("Cover lookup '$title' by '$artist' (album=$album) collections=$collectionIds netErr=${collectionResolution.hadNetworkFailure}")
            if (collectionIds.isEmpty()) {
                Timber.tag(TAG).d("No animated artwork collection ID for $title")
                if (!collectionResolution.hadNetworkFailure) {
                    cacheResult(null)
                }
                null
            } else {
                val token = withContext(Dispatchers.IO) { getDevToken(country) }
                if (token == null) {
                    Timber.tag(TAG).w("Cannot get Apple Music dev token")
                    null
                } else {
                    var resolved: AnimatedCoverResult? = null
                    var candidateNetworkFailure = collectionResolution.hadNetworkFailure
                    for (collectionId in collectionIds) {
                        val motionFetch =
                            withContext(Dispatchers.IO) {
                                fetchMotionVideo(collectionId, token, country, language)
                            }
                        if (motionFetch.hadNetworkFailure) candidateNetworkFailure = true
                        val motionVideo = motionFetch.videos ?: continue
                        val variantUrl =
                            try {
                                withContext(Dispatchers.IO) {
                                    resolveBestVariant(motionVideo.url, resolution)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                currentCoroutineContext().ensureActive()
                                candidateNetworkFailure = true
                                Timber.tag(TAG).w("Animated artwork candidate failed (${e::class.simpleName})")
                                continue
                            }
                        if (variantUrl != null && urlTrustCheck(variantUrl)) {
                            val tallResult =
                                if (motionVideo.tallUrl == motionVideo.url) {
                                    Result.success(variantUrl)
                                } else {
                                    resolveTallVariant(motionVideo.tallUrl, resolution)
                                }
                            val tallUrl = tallResult.getOrNull()
                            if (tallResult.isSuccess) {
                                cacheResult(variantUrl, tallUrl)
                            }
                            resolved = AnimatedCoverResult(url = variantUrl, tallUrl = tallUrl)
                            break
                        }
                    }
                    if (resolved == null && !candidateNetworkFailure) {
                        cacheResult(null)
                    }
                    resolved
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w("Animated artwork fetch failed (${e::class.simpleName})")
            null
        }
    }

    suspend fun clearCache() {
        cacheLoadJob.join()
        cacheMutex.withLock {
            cache.clear()
            artistCache.clear()
            cacheGeneration++
        }
        tokenMutex.withLock {
            tokenCache = null
        }
        saveJob?.cancel()
        artistSaveJob?.cancel()
        withContext(Dispatchers.IO) {
            runCatching { coverCacheFile.delete() }
            runCatching { artistCacheFile.delete() }
        }
        Timber.tag(TAG).d("Animated artwork metadata caches cleared")
    }

    private fun loadCacheFromDisk() {
        runCatching {
            if (!coverCacheFile.isFile) return
            val raw = coverCacheFile.readText()
            if (raw.isBlank() || raw == "{}") return
            val obj = json.parseToJsonElement(raw).jsonObject
            val now = timeSecondsProvider()
            var changed = false
            for ((key, value) in obj) {
                if (!key.startsWith("$CACHE_KEY_VERSION|")) {
                    changed = true
                    continue
                }
                val entry = parseCacheEntry(value)
                val maxAge = if (entry?.url == null) CACHE_MISS_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS
                if (entry == null || now - entry.cachedAt > maxAge) {
                    changed = true
                    continue
                }
                val url = entry.url
                if (url != null && !urlTrustCheck(url)) {
                    changed = true
                    continue
                }
                cache[key] = entry.copy(tallUrl = entry.tallUrl?.takeIf(urlTrustCheck))
            }
            while (cache.size > MAX_COVER_CACHE_ENTRIES) {
                cache.minByOrNull { it.value.cachedAt }?.key?.let(cache::remove)
                changed = true
            }
            if (changed) {
                scope.launch { saveCacheToDisk() }
            }
        }.onFailure {
            Timber.tag(TAG).w("Failed to load animated cover cache (${it::class.simpleName})")
        }
    }

    private fun loadArtistCacheFromDisk() {
        runCatching {
            if (!artistCacheFile.isFile) return
            val raw = artistCacheFile.readText()
            if (raw.isBlank() || raw == "{}") return
            val now = timeSecondsProvider()
            json
                .decodeFromString(artistCacheSerializer, raw)
                .entries
                .sortedByDescending { it.value.cachedAt }
                .take(MAX_ARTIST_CACHE_ENTRIES)
                .forEach { (key, entry) ->
                    val maxAge = if (entry.shortLived) ARTIST_NEGATIVE_CACHE_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS
                    if (
                        now - entry.cachedAt <= maxAge &&
                        entry.imageUrl?.let(urlTrustCheck) != false &&
                        entry.videoUrl?.let(urlTrustCheck) != false
                    ) {
                        artistCache[key] = entry
                    }
                }
        }.onFailure {
            Timber.tag(TAG).w("Failed to load artist artwork cache (${it::class.simpleName})")
        }
    }

    private fun parseCacheEntry(value: JsonElement): CacheEntry? {
        val obj = value as? JsonObject ?: return null
        val url =
            when (val u = obj["url"]) {
                is JsonNull -> null
                is JsonPrimitive -> u.content.ifBlank { null }
                else -> null
            }
        val cachedAt = (obj["cachedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if (cachedAt <= 0L) return null
        val tallUrl = (obj["tallUrl"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.ifBlank { null }
        return CacheEntry(url = url, cachedAt = cachedAt, tallUrl = tallUrl)
    }

    private suspend fun cacheAndSave(
        key: String,
        value: String?,
        tallValue: String?,
        generation: Long,
    ) {
        if (value != null && !urlTrustCheck(value)) {
            Timber.tag(TAG).w("Refusing to cache untrusted animated artwork URL")
            return
        }
        val saved =
            cacheMutex.withLock {
                if (cacheGeneration != generation) return@withLock false
                cache[key] =
                    CacheEntry(
                        url = value,
                        cachedAt = timeSecondsProvider(),
                        tallUrl = tallValue?.takeIf(urlTrustCheck),
                    )
                while (cache.size > MAX_COVER_CACHE_ENTRIES) {
                    cache.minByOrNull { it.value.cachedAt }?.key?.let(cache::remove)
                }
                true
            }
        if (!saved) return
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(2_000L)
                saveCacheToDisk()
            }
    }

    private suspend fun saveCacheToDisk() {
        try {
            val (snapshot, generation) = cacheMutex.withLock { cache.toMap() to cacheGeneration }
            val map =
                snapshot.mapValues { (_, entry) ->
                    JsonObject(
                        mapOf(
                            "url" to (entry.url?.let(::JsonPrimitive) ?: JsonNull),
                            "tallUrl" to (entry.tallUrl?.let(::JsonPrimitive) ?: JsonNull),
                            "cachedAt" to JsonPrimitive(entry.cachedAt),
                        ),
                    )
                }
            val encoded = json.encodeToString(JsonObject.serializer(), JsonObject(map))
            cacheMutex.withLock {
                if (cacheGeneration == generation) {
                    writeAtomic(coverCacheFile, encoded)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to save animated cover cache (${e::class.simpleName})")
        }
    }

    private fun scheduleArtistCacheSave() {
        artistSaveJob?.cancel()
        artistSaveJob =
            scope.launch {
                try {
                    delay(500L)
                    val (snapshot, generation) = cacheMutex.withLock { artistCache.toMap() to cacheGeneration }
                    val encoded = json.encodeToString(artistCacheSerializer, snapshot)
                    cacheMutex.withLock {
                        if (cacheGeneration == generation) {
                            writeAtomic(artistCacheFile, encoded)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Failed to save artist artwork cache (${e::class.simpleName})")
                }
            }
    }

    private fun writeAtomic(
        target: File,
        content: String,
    ) {
        val parent = target.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        val temp = File(parent, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.writeText(content)
            temp.delete()
        }
    }

    private fun buildCacheKey(
        title: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        resolution: AnimatedCoverResolution,
        country: String,
    ): String {
        val cleanArtist = escapeCacheComponent(artist.lowercase().trim())
        val cleanAlbum = normalizeAlbumOrNull(album)
        val rawTitleNormalized = normalizeTitleText(title)
        val key =
            when {
                !cleanAlbum.isNullOrBlank() && rawTitleNormalized == cleanTitleText(title) -> {
                    "$cleanArtist|A|${escapeCacheComponent(cleanAlbum)}"
                }

                !cleanAlbum.isNullOrBlank() -> {
                    "$cleanArtist|A|${escapeCacheComponent(cleanAlbum)}|${escapeCacheComponent(rawTitleNormalized)}"
                }

                rawTitleNormalized != cleanTitleText(title) -> {
                    "$cleanArtist|T|${escapeCacheComponent(rawTitleNormalized)}"
                }

                else -> {
                    "$cleanArtist|T|${escapeCacheComponent(cleanTitleText(title))}"
                }
            }
        val explicitKey = explicit?.let { if (it) "|E" else "|C" }.orEmpty()
        return "$CACHE_KEY_VERSION|$key$explicitKey|R${resolution.name}|C$country"
    }

    private suspend fun resolveCollectionIds(
        title: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        country: String,
    ): CollectionResolution {
        val cleanTitle = cleanTitleText(title)
        val cleanArtist = normalizeArtist(artist)
        val cleanAlbum = normalizeAlbumOrNull(album)
        val isVariant = isVariantTitle(title, cleanTitle)

        val seen = mutableSetOf<Long>()
        val allResults = mutableListOf<Pair<AniTunesSearchResult, String>>()
        var hadNetworkFailure = false

        fun matchingAlbums(): List<Long> {
            if (cleanAlbum != null) {
                val albumMatches =
                    allResults
                        .filter { (r, _) ->
                            matchesArtist(r, cleanArtist) && (explicit == null || matchesExplicit(r, explicit, true))
                        }.filter { (r, _) ->
                            !isVariant || matchesTitle(r, cleanTitle, title)
                        }.filter { (r, _) ->
                            val collectionName = r.collectionName
                            collectionName != null &&
                                (
                                    stripReleaseSuffixes(normalizeAlbum(collectionName)) != cleanTitle ||
                                        stripReleaseSuffixes(cleanAlbum) == cleanTitle
                                ) &&
                                albumNamesMatch(collectionName, cleanAlbum)
                        }
                if (albumMatches.isNotEmpty()) {
                    val exactAlbumMatches =
                        albumMatches.filter {
                            it.first.collectionName
                                ?.let(::normalizeAlbum)
                                ?.let(::stripArtworkEditionSuffix) == cleanAlbum
                        }
                    val candidates = exactAlbumMatches.ifEmpty { albumMatches }
                    return candidates.mapNotNull { it.first.collectionId }.distinct().take(MAX_COLLECTION_CANDIDATES)
                }
            }
            return emptyList()
        }

        if (cleanAlbum != null) {
            val albumSearch = fetchSearchResults("$artist $cleanAlbum", country, seen)
            if (albumSearch.hadNetworkFailure) hadNetworkFailure = true
            albumSearch.items.forEach { r ->
                allResults.add(r to "album")
            }
        }

        matchingAlbums().takeIf { it.isNotEmpty() }?.let { return CollectionResolution(it, hadNetworkFailure) }

        val titleSearch = fetchSearchResults("$artist $cleanTitle", country, seen)
        if (titleSearch.hadNetworkFailure) hadNetworkFailure = true
        titleSearch.items.forEach { r ->
            allResults.add(r to "title")
        }

        val hasGoodMatch =
            allResults.any { (r, _) ->
                matchesArtist(r, cleanArtist) &&
                    matchesTitle(r, cleanTitle, title) &&
                    (cleanAlbum == null || r.collectionName?.let { albumNamesMatch(it, cleanAlbum) } == true) &&
                    (explicit == null || matchesExplicit(r, explicit, cleanAlbum != null))
            }
        if (!hasGoodMatch || allResults.size < 5) {
            val artistIds =
                resolveArtistCandidates(
                    artistName = cleanArtist,
                    preserveFullName = false,
                    country = country,
                    language = null,
                    propagateFailures = false,
                )
            for (artistId in artistIds.take(1)) {
                val artistLookup = fetchArtistResults(artistId, country, seen)
                if (artistLookup.hadNetworkFailure) hadNetworkFailure = true
                artistLookup.items.forEach { r ->
                    if (matchesArtist(r, cleanArtist)) {
                        allResults.add(r to "artist_lookup")
                    }
                }
            }
        }

        if (allResults.isEmpty()) return CollectionResolution(emptyList(), hadNetworkFailure)

        val artistMatches =
            allResults.filter { (r, _) ->
                matchesArtist(r, cleanArtist) && (explicit == null || matchesExplicit(r, explicit, cleanAlbum != null))
            }

        matchingAlbums().takeIf { it.isNotEmpty() }?.let { return CollectionResolution(it, hadNetworkFailure) }

        val titleMatches = artistMatches.filter { (r, _) -> matchesTitle(r, cleanTitle, title) }
        val selfTitledTitle = if (isVariant) normalizeTitleText(title) else cleanTitle
        val selfTitledMatches = titleMatches.filter { (r, _) -> isSelfTitledRelease(r.collectionName, selfTitledTitle) }
        if (cleanAlbum == null || !isVariant) {
            if (cleanAlbum == null && selfTitledMatches.isNotEmpty()) {
                return CollectionResolution(
                    selfTitledMatches.mapNotNull { it.first.collectionId }.distinct().take(MAX_COLLECTION_CANDIDATES),
                    hadNetworkFailure,
                )
            }
            if (titleMatches.isNotEmpty()) {
                return CollectionResolution(
                    titleMatches.mapNotNull { it.first.collectionId }.distinct().take(MAX_COLLECTION_CANDIDATES),
                    hadNetworkFailure,
                )
            }
        }

        return CollectionResolution(emptyList(), hadNetworkFailure)
    }

    private suspend fun fetchSearchResults(
        query: String,
        country: String,
        seen: MutableSet<Long>,
    ): SearchFetchResult {
        val url =
            "${endpoints.itunesBaseUrl}/search"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("term", query)
                ?.addQueryParameter("entity", "song")
                ?.addQueryParameter("limit", "50")
                ?.addQueryParameter("country", country)
                ?.build()
                ?: return SearchFetchResult(emptyList(), false)
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = withTimeout(8_000L) { httpClientProvider().executeTextLimited(request, MAX_JSON_RESPONSE_BYTES) }
            val body = response.body
            if (!response.isSuccessful || body.isNullOrBlank()) {
                SearchFetchResult(emptyList(), !response.isSuccessful)
            } else {
                val parsed = json.decodeFromString(AniTunesSearchResponse.serializer(), body)
                val items =
                    parsed.results.filter { it.kind == "song" && it.trackId !in seen }.also { filtered ->
                        filtered.forEach { seen.add(it.trackId) }
                    }
                SearchFetchResult(items, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w("iTunes search failed (${e::class.simpleName})")
            SearchFetchResult(emptyList(), true)
        }
    }

    private suspend fun fetchArtistResults(
        artistId: Long,
        country: String,
        seen: MutableSet<Long>,
    ): SearchFetchResult {
        val url =
            "${endpoints.itunesBaseUrl}/lookup"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("id", artistId.toString())
                ?.addQueryParameter("entity", "song")
                ?.addQueryParameter("limit", "200")
                ?.addQueryParameter("country", country)
                ?.build()
                ?: return SearchFetchResult(emptyList(), false)
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = withTimeout(10_000L) { httpClientProvider().executeTextLimited(request, MAX_JSON_RESPONSE_BYTES) }
            val body = response.body
            if (!response.isSuccessful || body.isNullOrBlank()) {
                SearchFetchResult(emptyList(), !response.isSuccessful)
            } else {
                val parsed = json.decodeFromString(AniTunesSearchResponse.serializer(), body)
                val items =
                    parsed.results.filter { it.kind == "song" && it.trackId !in seen }.also { filtered ->
                        filtered.forEach { seen.add(it.trackId) }
                    }
                SearchFetchResult(items, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w("iTunes artist lookup failed (${e::class.simpleName})")
            SearchFetchResult(emptyList(), true)
        }
    }

    private suspend fun resolveArtistCandidates(
        artistName: String,
        preserveFullName: Boolean = false,
        country: String,
        language: String? = null,
        propagateFailures: Boolean = false,
    ): List<Long> {
        val url =
            "${endpoints.itunesBaseUrl}/search"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("term", artistName)
                ?.addQueryParameter("entity", "musicArtist")
                ?.addQueryParameter("attribute", "artistTerm")
                ?.addQueryParameter("limit", "200")
                ?.addQueryParameter("country", country)
                ?.build()
                ?: return emptyList()
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = withTimeout(8_000L) { httpClientProvider().executeTextLimited(request, MAX_JSON_RESPONSE_BYTES) }
            val body = response.body
            if (!response.isSuccessful || body.isNullOrBlank()) {
                if (propagateFailures) throw ArtistArtworkFetchException("Artist ID lookup unavailable (${response.code})")
                return emptyList()
            }
            val parsed = json.decodeFromString(AniTunesArtistSearchResponse.serializer(), body)
            val targetName = if (preserveFullName) normalizeArtistProfileName(artistName) else normalizeArtist(artistName)
            val matches =
                parsed.results
                    .filter { result ->
                        val resultName =
                            if (preserveFullName) normalizeArtistProfileName(result.artistName) else normalizeArtist(result.artistName)
                        resultName == targetName && result.artistId > 0L
                    }.map { it.artistId }
                    .distinct()
                    .take(2)
            if (matches.isNotEmpty() || !preserveFullName) {
                matches
            } else {
                searchAmpArtistIds(artistName, targetName, country, language)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w("Artist ID lookup failed (${e::class.simpleName})")
            if (propagateFailures) throw e
            emptyList()
        }
    }

    private suspend fun searchAmpArtistIds(
        artistName: String,
        normalizedTarget: String,
        country: String,
        language: String?,
    ): List<Long> {
        val token = getDevToken(country) ?: return emptyList()
        val isAsciiName = artistName.all { it.code < 128 }
        val searchLocale =
            if (isAsciiName) {
                "en-$country"
            } else {
                formatAppleLocaleParam(language, country)
            }
        val url =
            "${endpoints.ampApiBaseUrl}/v1/catalog/$country/search"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("types", "artists")
                ?.addQueryParameter("limit", "10")
                ?.addQueryParameter("term", artistName)
                ?.apply {
                    searchLocale?.let { addQueryParameter("l", it) }
                }?.build()
                ?: return emptyList()
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .header("Accept", "application/json")
                .get()
                .build()
        val response = withTimeout(10_000L) { httpClientProvider().executeTextLimited(request, MAX_JSON_RESPONSE_BYTES) }
        val body = response.body.takeIf { response.isSuccessful } ?: return emptyList()
        val data =
            runCatching {
                json.parseToJsonElement(body)
                    .jsonObject["results"]
                    ?.jsonObject
                    ?.get("artists")
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonArray
            }.getOrNull() ?: return emptyList()
        return data
            .mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
                val name =
                    (obj["attributes"] as? JsonObject)
                        ?.get("name")
                        ?.let { it as? JsonPrimitive }
                        ?.content
                        ?: return@mapNotNull null
                if (normalizeArtistProfileName(name) == normalizedTarget) id else null
            }.distinct()
            .take(2)
    }

    private fun matchesArtist(
        result: AniTunesSearchResult,
        cleanArtist: String,
    ): Boolean = normalizeArtist(result.artistName) == cleanArtist

    private fun matchesExplicit(
        result: AniTunesSearchResult,
        explicit: Boolean,
        albumKnown: Boolean,
    ): Boolean =
        matchesAppleMusicExplicitness(
            trackExplicitness = result.trackExplicitness,
            collectionExplicitness = result.collectionExplicitness,
            explicit = explicit,
            albumKnown = albumKnown,
        )

    private fun matchesTitle(
        result: AniTunesSearchResult,
        cleanTitle: String,
        rawTitle: String,
    ): Boolean {
        if (normalizeTrackTitle(result.trackName) == normalizeTrackTitle(rawTitle)) return true
        return !isVariantTitle(rawTitle, cleanTitle) && cleanTitleText(result.trackName) == cleanTitle
    }

    private suspend fun getDevToken(country: String): String? {
        val storefront = country.lowercase().takeIf(String::isNotBlank) ?: return null
        val now = timeSecondsProvider()
        tokenMutex.withLock {
            tokenCache?.let { if (it.expiresAt - now > 300) return it.token }
        }

        return try {
            val browseRequest =
                Request.Builder()
                    .url("${endpoints.appleMusicWebBaseUrl}/$storefront/browse")
                    .header("User-Agent", UA)
                    .header("Accept", "text/html")
                    .get()
                    .build()
            val browseResponse = withTimeout(15_000L) { httpClientProvider().executeTextLimited(browseRequest, MAX_HTML_RESPONSE_BYTES) }
            val browseHtml = browseResponse.body.takeIf { browseResponse.isSuccessful } ?: return null

            val bundlePath = bundlePathRegex.find(browseHtml)?.value ?: return null
            val jsUrl = "${endpoints.appleMusicWebBaseUrl}$bundlePath"
            val jsRequest =
                Request.Builder()
                    .url(jsUrl)
                    .header("User-Agent", UA)
                    .get()
                    .build()
            val jsResponse = withTimeout(15_000L) { httpClientProvider().executeTextLimited(jsRequest, MAX_SCRIPT_RESPONSE_BYTES) }
            val js = jsResponse.body.takeIf { jsResponse.isSuccessful } ?: return null

            val token =
                jwtRegex
                    .findAll(js)
                    .mapNotNull { match ->
                        val payloadSegment = match.value.split('.').getOrNull(1) ?: return@mapNotNull null
                        val payloadText = decodeBase64Url(payloadSegment) ?: return@mapNotNull null
                        val payload = runCatching { json.decodeFromString(TokenPayload.serializer(), payloadText) }.getOrNull() ?: return@mapNotNull null
                        val expiry = payload.exp?.takeIf { it > now + 300 } ?: return@mapNotNull null
                        match.value.takeIf { payload.iss == "AMPWebPlay" }?.let { TokenCache(it, expiry) }
                    }.maxByOrNull { it.expiresAt }
                    ?: return null

            tokenMutex.withLock { tokenCache = token }
            token.token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w("Failed to get Apple Music dev token (${e::class.simpleName})")
            null
        }
    }

    private fun decodeBase64Url(input: String): String? =
        runCatching {
            val decoded = Base64.getUrlDecoder().decode(input)
            String(decoded, Charsets.UTF_8)
        }.getOrNull()

    private suspend fun fetchArtistAssets(
        artistId: Long,
        token: String,
        country: String,
        language: String,
        includeImage: Boolean,
        includeVideo: Boolean,
        videoResolution: AnimatedCoverResolution,
    ): ArtistArtworkResult? {
        val urlBuilder =
            "${endpoints.ampApiBaseUrl}/v1/catalog/$country/artists/$artistId"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("art[url]", "c,f")
                ?.apply {
                    if (includeVideo) addQueryParameter("extend", "editorialVideo")
                    addQueryParameter("format[resources]", "map")
                    formatAppleLocaleParam(language, country)?.let { addQueryParameter("l", it) }
                    addQueryParameter("platform", "web")
                }
                ?.build()
                ?: return null

        val request =
            Request.Builder()
                .url(urlBuilder)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .header("Accept", "application/json")
                .get()
                .build()
        val response = withTimeout(15_000L) { httpClientProvider().executeTextLimited(request, MAX_JSON_RESPONSE_BYTES) }
        if (response.code == 401) {
            tokenMutex.withLock { tokenCache = null }
        }
        if (!response.isSuccessful) throw ArtistArtworkFetchException("Artist assets HTTP ${response.code}")
        val body = response.body ?: throw ArtistArtworkFetchException("Artist assets body unavailable")

        val root = json.parseToJsonElement(body).jsonObject
        val attributes =
            (root["resources"] as? JsonObject)
                ?.get("artists")
                ?.let { it as? JsonObject }
                ?.get(artistId.toString())
                ?.let { it as? JsonObject }
                ?.get("attributes")
                ?.let { it as? JsonObject }
                ?: (root["data"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.get("attributes")
                    ?.let { it as? JsonObject }
                ?: return null

        val artwork = attributes["artwork"] as? JsonObject
        val imageUrl = if (includeImage) artwork?.let(::resolveArtistImageUrl) else null
        val editorialVideo = if (includeVideo) attributes["editorialVideo"] as? JsonObject else null
        val videoAsset =
            listOf("motionArtistWide16x9", "motionArtistFullscreen16x9", "motionArtistSquare1x1")
                .firstNotNullOfOrNull { key ->
                    (editorialVideo?.get(key) as? JsonObject)?.takeIf {
                        (it["video"] as? JsonPrimitive)?.content?.isNotBlank() == true
                    }
                }
        val masterUrl = (videoAsset?.get("video") as? JsonPrimitive)?.content
        val videoUrl = masterUrl?.let { resolveBestVariant(it, videoResolution) }?.takeIf(urlTrustCheck)
        return if (imageUrl != null || videoUrl != null) ArtistArtworkResult(imageUrl, videoUrl) else null
    }

    private fun resolveArtistImageUrl(artwork: JsonObject): String? {
        val template = (artwork["url"] as? JsonPrimitive)?.content ?: return null
        val width = (artwork["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val height = (artwork["height"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val url =
            template
                .replace("{w}", width.toString())
                .replace("{h}", height.toString())
                .replace("{c}", "bb")
                .replace("{f}", "webp")
        return url.takeIf(urlTrustCheck)
    }

    private suspend fun fetchMotionVideo(
        collectionId: Long,
        token: String,
        country: String,
        language: String?,
    ): MotionVideoFetchResult {
        val url =
            "${endpoints.ampApiBaseUrl}/v1/catalog/$country/albums/$collectionId"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("art[url]", "f")
                ?.addQueryParameter("extend", "editorialArtwork,editorialVideo,extendedAssetUrls,offers,seoDescription,seoTitle")
                ?.addQueryParameter("fields[artists]", "name,url")
                ?.addQueryParameter("fields[curators]", "name")
                ?.addQueryParameter("fields[record-labels]", "name,url")
                ?.addQueryParameter("format[resources]", "map")
                ?.addQueryParameter("include", "record-labels,artists")
                ?.addQueryParameter("include[music-videos]", "artists")
                ?.addQueryParameter("include[playlists]", "curator")
                ?.addQueryParameter("include[songs]", "artists,composers,albums")
                ?.apply {
                    formatAppleLocaleParam(language, country)?.let { addQueryParameter("l", it) }
                }
                ?.addQueryParameter("meta[albums:tracks]", "popularity")
                ?.addQueryParameter("platform", "web")
                ?.addQueryParameter(
                    "views",
                    "appears-on,audio-extras,more-by-artist,other-versions,related-videos,video-extras,you-might-also-like",
                )
                ?.build()
                ?: return MotionVideoFetchResult(null, false)

        return try {
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Origin", "https://music.apple.com")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            val response = withTimeout(15_000L) { httpClientProvider().executeTextLimited(request, MAX_JSON_RESPONSE_BYTES) }
            if (response.code == 401) {
                tokenMutex.withLock { tokenCache = null }
                Timber.tag(TAG).w("AMP API 401 - token rejected")
            }
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("AMP API returned ${response.code}")
                return MotionVideoFetchResult(null, response.code >= 500 || response.code == 429 || response.code == 401)
            }
            val body = response.body ?: return MotionVideoFetchResult(null, false)
            val parsed = json.parseToJsonElement(body)
            val attributes =
                parsed.jsonObject["resources"]
                    ?.jsonObject
                    ?.get("albums")
                    ?.jsonObject
                    ?.get(collectionId.toString())
                    ?.jsonObject
                    ?.get("attributes")
                    ?.jsonObject
                    ?: parsed.jsonObject["data"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("attributes")
                        ?.jsonObject

            val ev = attributes?.get("editorialVideo")?.jsonObject ?: return MotionVideoFetchResult(null, false)

            fun videoUrl(key: String): String? =
                (ev[key] as? JsonObject)
                    ?.get("video")
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { it.isNotBlank() }

            val tall = listOf("motionTallVideo3x4", "motionDetailTall").firstNotNullOfOrNull(::videoUrl)
            val square =
                listOf("motionSquareVideo1x1", "motionDetailSquare").firstNotNullOfOrNull(::videoUrl)
                    ?: tall
                    ?: ev.keys.firstNotNullOfOrNull(::videoUrl)
            MotionVideoFetchResult(square?.let { MotionVideos(url = it, tallUrl = tall) }, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w("AMP API error (${e::class.simpleName})")
            MotionVideoFetchResult(null, true)
        }
    }

    private suspend fun resolveTallVariant(
        masterUrl: String?,
        resolution: AnimatedCoverResolution,
    ): Result<String?> {
        masterUrl ?: return Result.success(null)
        return try {
            val resolved = withContext(Dispatchers.IO) { resolveBestVariant(masterUrl, resolution) }
            Result.success(resolved?.takeIf(urlTrustCheck))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        }
    }

    private suspend fun resolveBestVariant(
        masterUrl: String,
        resolution: AnimatedCoverResolution,
    ): String? {
        if (!urlTrustCheck(masterUrl)) {
            Timber.tag(TAG).w("Refusing to resolve untrusted animated artwork master URL")
            return null
        }
        val parsed = masterUrl.toHttpUrlOrNull() ?: return null
        val path = parsed.encodedPath
        if (path.endsWith(".mp4", ignoreCase = true)) return masterUrl
        if (!path.endsWith(".m3u8", ignoreCase = true)) return null

        val request = Request.Builder().url(parsed).get().build()
        val response = withTimeout(10_000L) { httpClientProvider().executeTextLimited(request, MAX_PLAYLIST_RESPONSE_BYTES) }
        if (!response.isSuccessful) throw ArtistArtworkFetchException("HLS HTTP ${response.code}")
        val playlist = response.body ?: throw ArtistArtworkFetchException("HLS body unavailable")
        return selectBestHlsVariant(masterUrl, playlist, resolution, urlTrustCheck)
    }

    private suspend fun OkHttpClient.executeTextLimited(
        request: Request,
        maxBytes: Int,
    ): HttpTextResult =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use { res ->
                            val text =
                                try {
                                    res.readTextLimited(maxBytes)
                                } catch (e: IOException) {
                                    if (continuation.isActive) continuation.resumeWithException(e)
                                    return
                                }
                            if (continuation.isActive) {
                                continuation.resume(
                                    HttpTextResult(
                                        code = res.code,
                                        isSuccessful = res.isSuccessful,
                                        body = text,
                                    ),
                                )
                            }
                        }
                    }
                },
            )
        }

    private data class HttpTextResult(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String?,
    )

    private class TokenCache(
        val token: String,
        val expiresAt: Long,
    )

    private class MotionVideos(
        val url: String,
        val tallUrl: String?,
    )

    private class MotionVideoFetchResult(
        val videos: MotionVideos?,
        val hadNetworkFailure: Boolean,
    )

    private class SearchFetchResult(
        val items: List<AniTunesSearchResult>,
        val hadNetworkFailure: Boolean,
    )

    private class CollectionResolution(
        val collectionIds: List<Long>,
        val hadNetworkFailure: Boolean,
    )

    private class ArtistArtworkFetchException(
        message: String,
    ) : Exception(message)

    companion object {
        private const val TAG = "AnimatedArtwork"
        private const val CACHE_SUBDIR = "animated_artwork_meta"
        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
        private const val CACHE_KEY_VERSION = "v12"
        private const val CACHE_MAX_AGE_SECONDS = 30L * 24 * 60 * 60
        private const val CACHE_MISS_MAX_AGE_SECONDS = 60L
        private const val ARTIST_NEGATIVE_CACHE_MAX_AGE_SECONDS = 6L * 60 * 60
        private const val MAX_COVER_CACHE_ENTRIES = 1_000
        private const val MAX_ARTIST_CACHE_ENTRIES = 500
        private const val MAX_COLLECTION_CANDIDATES = 8
        private const val MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_HTML_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_SCRIPT_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val MAX_PLAYLIST_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_METADATA_CHARS = 4_096

        private val bundlePathRegex = Regex("""/assets/index[^"'\s]*\.js""")
        private val jwtRegex = Regex("""eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    }
}

data class AnimatedCoverResult(
    val url: String,
    val tallUrl: String? = null,
)

data class ArtistArtworkResult(
    val imageUrl: String?,
    val videoUrl: String?,
)

sealed interface ArtistArtworkFetchResult {
    data class Found(
        val artwork: ArtistArtworkResult,
    ) : ArtistArtworkFetchResult

    data object Missing : ArtistArtworkFetchResult

    data object Unavailable : ArtistArtworkFetchResult
}

@Serializable
private data class ArtistCacheEntry(
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val cachedAt: Long = 0L,
    val isMissing: Boolean = imageUrl == null && videoUrl == null,
    val shortLived: Boolean = videoUrl == null,
)

@Serializable
private data class CacheEntry(
    val url: String? = null,
    val cachedAt: Long = 0L,
    val tallUrl: String? = null,
)

@Serializable
private data class TokenPayload(
    val iss: String? = null,
    val exp: Long? = null,
)

@Serializable
private data class AniTunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<AniTunesSearchResult> = emptyList(),
)

@Serializable
private data class AniTunesSearchResult(
    val trackName: String = "",
    val artistName: String = "",
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val collectionExplicitness: String? = null,
    val trackExplicitness: String? = null,
    val kind: String = "",
    val trackId: Long = 0,
)

@Serializable
private data class AniTunesArtistSearchResponse(
    val resultCount: Int = 0,
    val results: List<AniTunesArtistResult> = emptyList(),
)

@Serializable
private data class AniTunesArtistResult(
    val artistName: String = "",
    val artistId: Long = 0,
)

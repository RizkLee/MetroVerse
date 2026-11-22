/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.edit
import com.metrolist.innertube.ExtractionCooldown
import com.metrolist.innertube.InnerTubeXTransport
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.utils.hasSupportedYouTubeAuthCookie
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackTransport
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.potoken.AndroidTokenProvider
import com.metrolist.music.utils.potoken.WebPoTokenHealth
import io.ktor.client.call.body
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import com.metrolist.innertubex.InnerTube as PlaybackInnerTube

/** The sole stream extraction entry point for the Android app. */
object InnerTubeXPlayer {
    private const val TAG = "InnerTubeXPlayer"
    private const val STREAM_CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_STREAM_TTL_SECONDS = 5 * 60
    private const val TOKENLESS_AUDIO_TIMEOUT_MS = 8_000L

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var currentBundle: ExtractionBundle? = null

    private val bundleMutex = Mutex()
    private val cooldownMutex = Mutex()
    private val streamClientFailures = ConcurrentHashMap<String, FailedStreamClients>()
    private var cooldownScope: InnerTubeXTransport.SessionKey? = null
    private var extractionCooldown = ExtractionCooldown()

    @Synchronized
    fun initialize(context: Context) {
        if (applicationContext == null) applicationContext = context.applicationContext
    }

    suspend fun prewarm() {
        val bundle = bundle()
        currentExtractionCooldown().run(resetOnSuccess = false) {
            bundle.extractor.prewarm()
        }
        tokenProvider.prewarm()
    }

    internal suspend fun disabledStreamClients(): Set<String> =
        requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" }
            .dataStore.data.first().disabledStreamClients()

    fun sessionKey(): InnerTubeXTransport.SessionKey = YouTube.extractionTransport().sessionKey

    fun ensureCurrentSession(expected: InnerTubeXTransport.SessionKey?) {
        if (expected != null && sessionKey() != expected) throw CancellationException("Playback session changed")
    }

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        contentHints: ContentHints = ContentHints(),
        allowBoundedRange: Boolean = true,
        allowCustomHeaders: Boolean = true,
        excludedForReceiver: Set<String> = emptySet(),
    ): Result<PlaybackData> {
        return try {
            val hints =
                contentHints.copy(
                    isUploaded =
                        contentHints.isUploaded == true ||
                            playlistId == "MLPT" ||
                            playlistId?.contains("MLPT") == true,
                ).withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    allowBoundedRange = allowBoundedRange,
                    allowProgressive = false,
                )
            val extraction = bundle()
            val sessionKey = sessionKey()
            val userDisabled = disabledStreamClients()
            val videoFailed = failuresForSession(videoId, sessionKey)
            val hardExclusions = userDisabled + excludedForReceiver
            val excludedClients = videoFailed + hardExclusions
            val resolvedQuality = audioQuality.toInnerTubeX(connectivityManager)
            val cooldown = currentExtractionCooldown()
            val stream =
                cooldown.run {
                    val tokenlessStream =
                        if (
                            WebPoTokenHealth.tokensRejected() &&
                            hints.isUploaded != true &&
                            hints.isLive != true &&
                            WebPoTokenHealth.TOKENLESS_AUDIO_CLIENT !in hardExclusions &&
                            WebPoTokenHealth.TOKENLESS_AUDIO_PROFILE !in excludedClients
                        ) {
                            try {
                                withTimeoutOrNull(TOKENLESS_AUDIO_TIMEOUT_MS) {
                                    extraction.extractor.extract(
                                        videoId = videoId,
                                        hints = hints.copy(playbackClientOverrideId = WebPoTokenHealth.TOKENLESS_AUDIO_CLIENT),
                                        excludedClients = excludedClients,
                                        audioQuality = resolvedQuality,
                                        clientPlaybackNonce = generateClientPlaybackNonce(),
                                    )
                                }
                            } catch (_: StreamResolveException) {
                                Timber.tag(TAG).w("Tokenless audio preference failed; trying remaining clients")
                                null
                            }
                        } else {
                            null
                        }

                    requireNotNull(
                        tokenlessStream ?: resolveWithClientFallback(excludedClients, hardExclusions) { clients, useLowQuality ->
                            extraction.extractor.extract(
                                videoId = videoId,
                                hints = hints,
                                excludedClients = clients,
                                audioQuality = if (useLowQuality) InnerTubeXAudioQuality.LOW else resolvedQuality,
                                clientPlaybackNonce = generateClientPlaybackNonce(),
                            )
                        },
                    ) { "InnerTubeX returned no playable stream" }
                }
            if (sessionKey() != sessionKey || sessionKey.transportGeneration != extraction.transportGeneration) {
                throw CancellationException("Playback session changed")
            }
            check(stream.sabrBootstrap == null) { "SABR is not supported by this playback engine" }
            if (!usesOriginalAudioTrack(stream.audioUrl, stream.sabrBootstrap?.audioFormat?.xtags)) {
                throw StreamResolveException(
                    reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                    message = "No original audio stream found for this track.",
                )
            }
            // Cast and the Wrapped player cannot apply per-stream headers or chunked requests.
            // Exclude such a client and resolve again instead of returning an unusable URL.
            if (!allowCustomHeaders && (stream.headers.isNotEmpty() || stream.requireBoundedRange || stream.useRangeChunks)) {
                check(stream.clientName !in excludedForReceiver) { "No compatible stream for this receiver" }
                return playerResponseForPlayback(
                    videoId,
                    playlistId,
                    audioQuality,
                    connectivityManager,
                    contentHints,
                    allowBoundedRange = false,
                    allowCustomHeaders = false,
                    excludedForReceiver = excludedForReceiver + stream.clientName,
                )
            }
            Result.success(stream.toPlaybackData(sessionKey))
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            val cause = error.cause
            Result.failure(
                if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) {
                    cause
                } else {
                    error
                },
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    internal suspend fun <T : Any> resolveWithClientFallback(
        excludedClients: Set<String>,
        hardExclusions: Set<String> = emptySet(),
        extract: suspend (Set<String>, Boolean) -> T?,
    ): T? {
        if (excludedClients == hardExclusions) return extract(hardExclusions, false)

        val alternative =
            try {
                extract(excludedClients, false)
            } catch (error: StreamResolveException) {
                if (error.reason != StreamResolveException.Reason.NO_PLAYABLE_STREAM) throw error
                null
            }
        if (alternative != null) return alternative

        Timber.tag(TAG).w("Alternate stream clients exhausted; retrying enabled clients at low quality")
        return extract(hardExclusions, true)
    }

    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
        contentHints: ContentHints = ContentHints(),
    ): Result<PlayerResponse> =
        try {
            coroutineScope {
                val transport = YouTube.extractionTransport()
                val expectedKey = transport.sessionKey
                val session = transport.innerTube.sessionSnapshot()
                val client = if (contentHints.isUploaded == true) YouTubeClient.TVHTML5 else YouTubeClient.WEB_REMIX
                val response =
                    transport.innerTube
                        .playerWithSessionBound(
                            client = client,
                            videoId = videoId,
                            playlistId = playlistId,
                            signatureTimestamp = null,
                            poToken = null,
                            requestVisitorData = session.visitorData,
                            requestSession = session,
                        ).body<PlayerResponse>()
                if (sessionKey() != expectedKey) throw CancellationException("Playback session changed")
                Result.success(response)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private var failureSessionKey: InnerTubeXTransport.SessionKey? = null

    private fun failuresForSession(
        videoId: String,
        sessionKey: InnerTubeXTransport.SessionKey,
    ): Set<String> =
        synchronized(streamClientFailures) {
            if (failureSessionKey != sessionKey) {
                streamClientFailures.clear()
                failureSessionKey = sessionKey
            }
            failedStreamClients(videoId)
        }

    internal fun markStreamClientFailed(
        videoId: String,
        clientName: String,
        profileId: String? = null,
        responseCode: Int? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val untokenizedProfile403 = responseCode == 403 && profileId?.endsWith("_nopo") == true
        if (responseCode == 403 && profileId?.endsWith("__po") == true) {
            WebPoTokenHealth.recordRejection(nowMs)
        }
        streamClientFailures.compute(videoId) { _, failures ->
            val stillCurrent = failures?.takeIf { (nowMs - it.failedAtMs) in 0 until STREAM_CLIENT_FAILURE_TTL_MS }
            val additions = buildSet {
                profileId?.takeIf { it.isNotBlank() }?.let(::add)
                val isDirect =
                    PlaybackClientCatalog.manifests
                        .firstOrNull { it.id == PlaybackClientCatalog.manifestIdFromProfileId(profileId) }
                        ?.transports
                        ?.contains(PlaybackTransport.DIRECT) == true
                if ((!untokenizedProfile403 && responseCode == 403 && !isDirect) || !untokenizedProfile403 || isEmpty()) {
                    add(clientName)
                }
            }
            FailedStreamClients(stillCurrent?.clientNames.orEmpty() + additions, nowMs)
        }
        clientHealthTrackerOrNull()?.recordPlaybackFailure(
            clientOrProfileId = profileId ?: clientName,
            kind =
                when (responseCode) {
                    401, 407 -> ClientFailureKind.TOKEN
                    403 -> ClientFailureKind.MEDIA_FORBIDDEN
                    else -> ClientFailureKind.MEDIA_FAILURE
                },
            hints = ContentHints(),
            authenticated = hasSupportedYouTubeAuthCookie(YouTube.cookie.orEmpty()),
        )
    }

    internal suspend fun onStreamForbiddenRejection(profileId: String?) {
        val untokenized = profileId?.endsWith("_nopo") == true
        if (!untokenized) {
            WebPoTokenHealth.recordRejection()
            runCatching { tokenProvider.invalidateAttestation() }
        }
    }

    internal fun reportStreamSuccess(
        clientOrProfileId: String?,
        isExplicit: Boolean? = null,
        isUploaded: Boolean? = null,
    ) {
        clientHealthTrackerOrNull()?.recordPlaybackSuccess(
            clientOrProfileId = clientOrProfileId,
            hints = ContentHints(isExplicit = isExplicit, isUploaded = isUploaded),
            authenticated = hasSupportedYouTubeAuthCookie(YouTube.cookie.orEmpty()),
        )
    }

    fun clearStreamClientFailures() {
        streamClientFailures.clear()
    }

    suspend fun refreshAfterStreamRejection(): Boolean = bundle().cipherService.refreshAfterStreamRejection()

    internal fun failedStreamClients(
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Set<String> {
        val failures = streamClientFailures[videoId] ?: return emptySet()
        if ((nowMs - failures.failedAtMs) !in 0 until STREAM_CLIENT_FAILURE_TTL_MS) {
            streamClientFailures.remove(videoId, failures)
            return emptySet()
        }
        return failures.clientNames
    }

    private suspend fun currentExtractionCooldown(): ExtractionCooldown {
        val key = sessionKey()
        return cooldownMutex.withLock {
            if (cooldownScope != key) {
                cooldownScope = key
                extractionCooldown = ExtractionCooldown()
            }
            extractionCooldown
        }
    }

    private suspend fun bundle(): ExtractionBundle {
        val transport = YouTube.extractionTransport()
        currentBundle?.takeIf { it.transportGeneration == transport.generation }?.let { return it }
        return bundleMutex.withLock {
            val latestTransport = YouTube.extractionTransport()
            currentBundle?.takeIf { it.transportGeneration == latestTransport.generation }?.let { return@withLock it }
            try {
                currentBundle?.cipherService?.dispose()
                if (currentBundle != null) tokenProvider.close()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.log(
                    com.metrolist.innertubex.InnerTubeLogEvent(
                        level = InnerTubeLogLevel.WARN,
                        tag = TAG,
                        message = "old cipher disposal failed",
                        details = mapOf("exceptionType" to (error::class.simpleName ?: "unknown")),
                    ),
                )
            }

            val remoteStore = RemotePlayerConfigStore(latestTransport.httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(latestTransport.httpClient, remoteStore, logger)
            val healthTracker = clientHealthTracker
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        YtConfigParserImpl(
                            latestTransport.httpClient,
                            latestTransport.innerTube,
                            remoteStore,
                            logger,
                            cipherService = cipherService,
                        ).withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = latestTransport.innerTube,
                    fallbackStrategy = ContentAwareFallbackStrategy(healthTracker),
                    tokenProvider = tokenProvider,
                    clientHealthMonitor = healthTracker,
                    logger = logger,
                )
            ExtractionBundle(latestTransport.generation, latestTransport.innerTube, cipherService, extractor).also {
                currentBundle = it
            }
        }
    }

    private val configRepository: PlayerConfigRepository by lazy {
        AndroidPlayerConfigRepository(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private val clientHealthTracker: ClientHealthTracker by lazy {
        ClientHealthTracker(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private fun clientHealthTrackerOrNull(): ClientHealthTracker? =
        applicationContext?.let { clientHealthTracker }

    private val tokenProvider: AndroidTokenProvider by lazy {
        AndroidTokenProvider(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private val logger =
        InnerTubeLogger { event ->
            val details = event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
                InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
                InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
                InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
            }
        }

    class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamClient: String,
        val profileId: String,
        val streamHeaders: Map<String, String>,
        val requireBoundedRange: Boolean,
        val rangeChunkSizeBytes: Long,
        val useRangeChunks: Boolean,
        val sessionKey: InnerTubeXTransport.SessionKey? = null,
    )

    private data class ExtractionBundle(
        val transportGeneration: Long,
        val innerTube: PlaybackInnerTube,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    private data class FailedStreamClients(
        val clientNames: Set<String>,
        val failedAtMs: Long,
    )

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) = preferences.edit { putString("json", value) }
        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) = preferences.edit { putLong("cached_at_ms", value) }
        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) = preferences.edit { putString("source_url", value) }
        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) = preferences.edit { putString("etag", value) }

        private companion object {
            const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"
        }
    }

    internal fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private fun AudioQuality.toInnerTubeX(connectivityManager: ConnectivityManager): InnerTubeXAudioQuality =
        when (this) {
            AudioQuality.HIGH -> InnerTubeXAudioQuality.HIGH
            AudioQuality.LOW -> InnerTubeXAudioQuality.LOW
            AudioQuality.AUTO ->
                if (connectivityManager.isActiveNetworkMetered) {
                    InnerTubeXAudioQuality.LOW
                } else {
                    InnerTubeXAudioQuality.AUTO
                }
        }

    internal fun ExtractedStream.toPlaybackData(sessionKey: InnerTubeXTransport.SessionKey? = null): PlaybackData {
        val metadata = mediaMetadata
        val tracking = playbackTracking
        val fullMimeType =
            if (codecs.isNullOrBlank()) {
                mimeType.orEmpty()
            } else {
                "${mimeType.orEmpty()}; codecs=\"$codecs\""
            }
        return PlaybackData(
            audioConfig =
                if (loudnessDb != null || perceptualLoudnessDb != null) {
                    PlayerResponse.PlayerConfig.AudioConfig(loudnessDb, perceptualLoudnessDb)
                } else {
                    null
                },
            videoDetails =
                metadata?.let {
                    PlayerResponse.VideoDetails(
                        videoId = videoId,
                        title = it.title,
                        author = it.author,
                        channelId = it.channelId.orEmpty(),
                        lengthSeconds = it.durationSeconds?.toString().orEmpty(),
                        musicVideoType = it.musicVideoType,
                        viewCount = it.viewCount,
                        thumbnail =
                            Thumbnails(
                                it.thumbnails.map { thumbnail ->
                                    Thumbnail(thumbnail.url, thumbnail.width, thumbnail.height)
                                },
                            ),
                    )
                },
            playbackTracking =
                tracking?.let {
                    PlayerResponse.PlaybackTracking(
                        videostatsPlaybackUrl =
                            it.playbackUrl?.let(PlayerResponse.PlaybackTracking::VideostatsPlaybackUrl),
                        videostatsWatchtimeUrl =
                            it.watchtimeUrl?.let(PlayerResponse.PlaybackTracking::VideostatsWatchtimeUrl),
                        atrUrl = null,
                    )
                },
            format =
                PlayerResponse.StreamingData.Format(
                    itag = itag,
                    url = audioUrl,
                    mimeType = fullMimeType,
                    bitrate = bitrate ?: 0,
                    width = null,
                    height = null,
                    contentLength = contentLengthBytes,
                    quality = "",
                    fps = null,
                    qualityLabel = null,
                    averageBitrate = bitrate,
                    audioQuality = null,
                    approxDurationMs = metadata?.durationSeconds?.times(1000)?.toString(),
                    audioSampleRate = sampleRate,
                    audioChannels = null,
                    loudnessDb = loudnessDb,
                    lastModified = null,
                    signatureCipher = null,
                    cipher = null,
                    audioTrack = null,
                ),
            streamUrl = audioUrl,
            streamExpiresInSeconds =
                expiresAt
                    ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                    ?.coerceAtLeast(1)
                    ?: DEFAULT_STREAM_TTL_SECONDS,
            streamClient = clientName,
            profileId = profileId,
            streamHeaders = headers,
            requireBoundedRange = this.requireBoundedRange,
            rangeChunkSizeBytes = this.rangeChunkSizeBytes,
            useRangeChunks = this.useRangeChunks,
            sessionKey = sessionKey,
        )
    }
}

internal fun usesOriginalAudioTrack(
    url: String,
    formatTags: String? = null,
): Boolean {
    val tags = runCatching { Url(url).parameters.getAll("xtags").orEmpty() }.getOrDefault(emptyList()) + listOfNotNull(formatTags)
    val audioContent = tags.mapNotNull(::audioContentTag)
    return audioContent.isEmpty() || audioContent.all { it == "original" }
}

private fun audioContentTag(tags: String): String? {
    tags
        .split(':', '&')
        .firstOrNull { it.startsWith("acont=") }
        ?.let { return it.substringAfter('=') }
    if (tags.length > 2048) return null

    val encoded = tags.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
    var offset = 0
    while (offset < bytes.size) {
        val outerTag = readProtoVarint(bytes, offset, bytes.size) ?: return null
        val outerLength = readProtoVarint(bytes, outerTag.second, bytes.size) ?: return null
        if (outerTag.first != 10 || outerLength.first < 0) return null
        val entryEnd = outerLength.second + outerLength.first
        if (entryEnd > bytes.size) return null

        var entryOffset = outerLength.second
        var key: String? = null
        var value: String? = null
        while (entryOffset < entryEnd) {
            val entryTag = readProtoVarint(bytes, entryOffset, entryEnd) ?: return null
            val length = readProtoVarint(bytes, entryTag.second, entryEnd) ?: return null
            if (entryTag.first and 7 != 2 || length.first < 0) return null
            val fieldEnd = length.second + length.first
            if (fieldEnd > entryEnd) return null
            val text = bytes.decodeToString(length.second, fieldEnd)
            when (entryTag.first ushr 3) {
                1 -> key = text
                2 -> value = text
            }
            entryOffset = fieldEnd
        }
        if (key == "acont") return value
        offset = entryEnd
    }
    return null
}

private fun readProtoVarint(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Pair<Int, Int>? {
    var value = 0
    var offset = start
    var shift = 0
    while (offset < limit && shift < 32) {
        val byte = bytes[offset++].toInt() and 0xff
        value = value or ((byte and 0x7f) shl shift)
        if (byte and 0x80 == 0) return value to offset
        shift += 7
    }
    return null
}

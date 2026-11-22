/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistConjunctions
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.kugou.KuGou
import com.metrolist.lastfm.LastFM
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.*
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.toInetSocketAddress
import com.metrolist.music.utils.CrashHandler
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.ResizableImageDiskCache
import com.metrolist.music.utils.SecureCredentialStore
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.utils.reportException
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Path.Companion.toPath
import okio.buffer
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class App :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        if (isPotokenProcess()) {
            Timber.plant(Timber.DebugTree())
            return
        }

        // Install crash handler first
        CrashHandler.install(this)

        // preferencesDataStore uses filesDir/datastore; proactive mkdir reduces failures on odd ROM states
        try {
            val datastoreDir = File(filesDir, "datastore")
            if (!datastoreDir.isDirectory && !datastoreDir.mkdirs()) {
                Timber.w("Could not create DataStore directory at ${datastoreDir.path}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to ensure DataStore directory")
        }

        // Plant logging BEFORE cipher init so the synchronous config-store load
        // (bundled asset + cached overlay) is captured, not just the async remote refresh.
        Timber.plant(Timber.DebugTree())

        InnerTubeXPlayer.initialize(this)

        // Pre-read Coil cache size on background to avoid runBlocking in newImageLoader
        applicationScope.launch(Dispatchers.IO) {
            cachedCoilCacheSize = dataStore.data.map { it[MaxImageCacheSizeKey] ?: 512 }.first()
        }

        // تهيئة إعدادات التطبيق عند الإقلاع
        applicationScope.launch {
            SecureCredentialStore.initializeAndMigrate(this@App)
            // The extractor must observe the configured proxy and persisted session before warm-up.
            initializeSettings()
            observeSettingsChanges()
            launch(Dispatchers.IO) {
                delay(2500)
                var waitedMs = 0
                while (YouTube.visitorData == null && waitedMs < 12_000) {
                    delay(500)
                    waitedMs += 500
                }
                runCatching { InnerTubeXPlayer.prewarm() }
            }
        }
    }

    private suspend fun initializeSettings() {
        val settings = dataStore.data.first()
        val locale = Locale.getDefault()
        val languageTag = locale.language
        safeDataStoreEdit { preferences ->
            com.metrolist.music.podcast.transcript.initializePodcastTranslationTarget(
                preferences, com.metrolist.music.podcast.transcript.podcastTranslationDefaultLanguage(
                    preferences[com.metrolist.music.constants.AppLanguageKey], resources.configuration.locales[0],
                ),
            )
        }

        ArtistConjunctions.conjunctions = listOf(
            R.string.and,
        ).mapNotNull { id ->
            runCatching { getString(id) }.getOrNull()
        }

        YouTube.locale =
            YouTubeLocale(
                gl =
                    settings[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.country.takeIf { it in CountryCodeToName }
                        ?: "US",
                hl =
                    settings[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.language.takeIf { it in LanguageCodeToName }
                        ?: languageTag.takeIf { it in LanguageCodeToName }
                        ?: "en",
            )

        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }

        // Initialize LastFM with API keys from BuildConfig (GitHub Secrets)
        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY.takeIf { it.isNotEmpty() } ?: "",
            secret = BuildConfig.LASTFM_SECRET.takeIf { it.isNotEmpty() } ?: "",
        )

        if (settings[ProxyEnabledKey] == true) {
            val username = settings[ProxyUsernameKey].orEmpty()
            val password = settings[ProxyPasswordKey].orEmpty()
            val type = settings[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP)

            if (username.isNotEmpty() || password.isNotEmpty()) {
                if (type == Proxy.Type.HTTP) {
                    YouTube.proxyAuth = Credentials.basic(username, password)
                } else {
                    Authenticator.setDefault(
                        object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication =
                                PasswordAuthentication(username, password.toCharArray())
                        },
                    )
                }
            }
            try {
                settings[ProxyUrlKey]?.let {
                    YouTube.proxy = Proxy(type, it.toInetSocketAddress())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@App, getString(R.string.failed_to_parse_proxy), Toast.LENGTH_SHORT).show()
                }
                reportException(e)
            }
        }

        YouTube.useLoginForBrowse = settings[UseLoginForBrowse] ?: true

        val channel =
            NotificationChannel(
                "updates",
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.update_channel_desc)
            }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun observeSettingsChanges() {
        applicationScope.launch(Dispatchers.IO) {
            combine(
                SecureCredentialStore.flow(this@App, InnerTubeCookieKey),
                SecureCredentialStore.flow(this@App, VisitorDataKey),
                SecureCredentialStore.flow(this@App, DataSyncIdKey),
                dataStore.data.map { it[InnerTubeAuthUserKey] ?: "0" },
            ) { rawCookie, rawVisitorData, rawDataSyncId, authUser ->
                listOf(
                    rawCookie,
                    rawVisitorData?.takeIf { it.isNotBlank() && it != "null" },
                    rawDataSyncId?.let {
                        it.takeIf { !it.contains("||") }
                            ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                            ?: it.substringAfter("||")
                    },
                    authUser,
                )
            }
                .distinctUntilChanged()
                .collect { (cookie, visitorData, dataSyncId, authUser) ->
                    try {
                        YouTube.replacePlaybackSession(cookie, visitorData, dataSyncId, authUser ?: "0")
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.e("Could not load playback session: %s", error.javaClass.simpleName)
                        // A transient session/configuration failure must not erase saved credentials.
                        return@collect
                    }
                    if (visitorData == null) {
                        try {
                            YouTube.visitorData().getOrNull()?.let { freshVisitorData ->
                                val currentVisitor = SecureCredentialStore.get(this@App, VisitorDataKey)
                                val visitorMissing = currentVisitor.isNullOrBlank() || currentVisitor == "null"
                                if (SecureCredentialStore.get(this@App, InnerTubeCookieKey) == cookie && visitorMissing) {
                                    SecureCredentialStore.put(this@App, VisitorDataKey, freshVisitorData)
                                }
                            }
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Timber.w("Visitor data refresh deferred: %s", error.javaClass.simpleName)
                        }
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            SecureCredentialStore.flow(this@App, LastFMSessionKey)
                .distinctUntilChanged()
                .collect { session ->
                    try {
                        LastFM.sessionKey = session
                    } catch (e: Exception) {
                        Timber.e("Error while loading last.fm session key. %s", e.message)
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { Triple(it[ContentCountryKey], it[ContentLanguageKey], it[AppLanguageKey]) }
                .distinctUntilChanged()
                .collect { (contentCountry, contentLanguage, appLanguage) ->
                    val systemLocale = Locale.getDefault()
                    val effectiveAppLocale =
                        appLanguage
                            ?.takeUnless { it == SYSTEM_DEFAULT }
                            ?.let { Locale.forLanguageTag(it) }
                            ?: systemLocale

                    YouTube.locale =
                        YouTubeLocale(
                            gl =
                                contentCountry?.takeIf { it != SYSTEM_DEFAULT }
                                    ?: effectiveAppLocale.country.takeIf { it in CountryCodeToName }
                                    ?: systemLocale.country.takeIf { it in CountryCodeToName }
                                    ?: "US",
                            hl =
                                contentLanguage?.takeIf { it != SYSTEM_DEFAULT }
                                    ?: effectiveAppLocale.toLanguageTag().takeIf { it in LanguageCodeToName }
                                    ?: effectiveAppLocale.language.takeIf { it in LanguageCodeToName }
                                    ?: "en",
                        )
                }
        }
    }

    @Volatile
    private var cachedCoilCacheSize: Int? = null

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val cacheSize = cachedCoilCacheSize ?: runBlocking {
            dataStore.data.map { it[MaxImageCacheSizeKey] ?: 512 }.first()
        }
        val artworkClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor(ResponseSizeLimitInterceptor)
            .build()
        return ImageLoader
            .Builder(this)
            .apply {
                crossfade(true)
                allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = { artworkClient },
                        ),
                    )
                }
                // Memory cache for fast image loading (prevents network requests on recomposition)
                memoryCache {
                    MemoryCache
                        .Builder()
                        .maxSizePercent(context, 0.15)
                        .build()
                }
                diskCache(
                    ResizableImageDiskCache(
                        directory = cacheDir.resolve("coil").absolutePath.toPath(),
                        maxSizeBytes = cacheSize * 1024 * 1024L,
                    ),
                )
                diskCachePolicy(CachePolicy.ENABLED)
                networkCachePolicy(CachePolicy.ENABLED)
            }.build()
    }

    private object ResponseSizeLimitInterceptor : Interceptor {
        private const val MAX_REMOTE_ARTWORK_BYTES = 12L * 1024 * 1024

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val url = request.url
            if ((url.scheme != "https" && url.scheme != "http") || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw IOException("Remote artwork URL is not trusted")
            }
            val response = chain.proceed(request)
            val body = response.body
            if (body.contentLength() > MAX_REMOTE_ARTWORK_BYTES) {
                response.close()
                throw IOException("Remote artwork exceeds the response size limit")
            }

            var bytesRead = 0L
            val limitedSource =
                object : ForwardingSource(body.source()) {
                    override fun read(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long {
                        val read = super.read(sink, minOf(byteCount, MAX_REMOTE_ARTWORK_BYTES + 1 - bytesRead))
                        if (read > 0L) bytesRead += read
                        if (bytesRead > MAX_REMOTE_ARTWORK_BYTES) {
                            response.close()
                            throw IOException("Remote artwork exceeds the response size limit")
                        }
                        return read
                    }
                }.buffer()
            return response
                .newBuilder()
                .body(limitedSource.asResponseBody(body.contentType(), body.contentLength()))
                .build()
        }
    }

    private fun isPotokenProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
            }.getOrDefault("")
        }
        return processName.endsWith(":potoken")
    }

    companion object {
        suspend fun forgetAccount(context: Context) {
            Timber.d("forgetAccount: Starting logout process")

            val secureCleared = SecureCredentialStore.remove(
                context,
                InnerTubeCookieKey,
                VisitorDataKey,
                DataSyncIdKey,
            )

            // Clear DataStore preferences
            Timber.d("forgetAccount: Clearing DataStore preferences")
            val cleared = context.safeDataStoreEdit { settings ->
                settings.remove(InnerTubeCookieKey)
                settings.remove(VisitorDataKey)
                settings.remove(DataSyncIdKey)
                settings.remove(InnerTubeAuthUserKey)
                settings.remove(AccountNameKey)
                settings.remove(AccountEmailKey)
                settings.remove(AccountChannelHandleKey)
            }
            if (!cleared || !secureCleared) {
                Timber.e("forgetAccount: Failed to clear DataStore preferences — proceeding with in-memory cleanup only")
            } else {
                Timber.d("forgetAccount: DataStore preferences cleared")
            }

            // Immediately clear YouTube object's auth state
            Timber.d("forgetAccount: Clearing YouTube object auth state")
            YouTube.replacePlaybackSession(null, null, null)
            Timber.d("forgetAccount: In-memory playback session cleared")

            // Clear WebView cookies to prevent auto-relogin
            Timber.d("forgetAccount: Clearing WebView CookieManager")
            withContext(Dispatchers.Main) {
                android.webkit.CookieManager.getInstance().apply {
                    removeAllCookies { removed ->
                        Timber.d("forgetAccount: CookieManager.removeAllCookies callback: removed=$removed")
                    }
                    flush()
                }
            }
            Timber.d("forgetAccount: Logout process complete")
        }
    }
}

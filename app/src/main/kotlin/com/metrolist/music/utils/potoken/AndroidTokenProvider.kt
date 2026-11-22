/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.potoken

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

class AndroidTokenProvider(
    context: Context,
) : TokenProvider {
    private val appContext = context.applicationContext

    override val capabilities =
        TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION),
            usesWebView = true,
        )

    private val mutex = Mutex()
    private val mintScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingMints = mutableMapOf<MintKey, Deferred<PoTokenResult?>>()
    private val resultCache = mutableMapOf<String, CachedResult>()
    private val remote by lazy { RemotePoTokenMinter(appContext) }
    private val failureCooldown = TokenFailureCooldown(networkScope = ::currentNetworkScope)
    private var minterVisitorData: String? = null
    private var minterCookie: String? = null

    suspend fun prewarm() {
        withContext(Dispatchers.Main.immediate) {
            try {
                remote.prewarm()
            } catch (error: Exception) {
                remote.close()
                Timber.tag(TAG).d("PoToken prewarm failed type=%s", error::class.simpleName)
            }
        }
    }

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        withContext(Dispatchers.Main.immediate) {
            awaitPoToken(videoId, visitorData, cookie)
        }

    private suspend fun awaitPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? {
        val key = MintKey(videoId, visitorData, cookie)
        while (true) {
            cachedResult(videoId, visitorData, cookie)?.let { return it }
            val request =
                pendingMints[key] ?: mintScope
                    .async(start = CoroutineStart.LAZY) {
                        mintPoToken(videoId, visitorData, cookie)
                    }.also { request ->
                        pendingMints[key] = request
                        request.invokeOnCompletion {
                            if (pendingMints[key] === request) pendingMints.remove(key)
                        }
                    }
            try {
                return request.await()
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun mintPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        mutex.withLock {
            val nowMs = System.currentTimeMillis()
            resultCache.entries.removeAll { nowMs >= it.value.expiresAtMs }
            val elapsedNowMs = SystemClock.elapsedRealtime()
            cachedResult(videoId, visitorData, cookie)?.let { return@withLock it }
            if (failureCooldown.isCoolingDown(visitorData, cookie)) {
                Timber.tag(TAG).d("getPoToken unavailable cooldown hit target=video")
                return@withLock null
            }

            try {
                if (minterVisitorData != null && (minterVisitorData != visitorData || minterCookie != cookie)) resetMinter()
                val minted = remote.mint(videoId, visitorData, cookie)
                minterVisitorData = visitorData
                minterCookie = cookie
                val result = minted.result
                failureCooldown.reset()
                resultCache[videoId] =
                    CachedResult(
                        visitorData = visitorData,
                        cookie = cookie,
                        result = result,
                        expiresAtMs = minOf(nowMs + TOKEN_CACHE_TTL_MS, minted.expiresAtMs),
                    )
                Timber.tag(TAG).d(
                    "getPoToken success target=video elapsed=%dms",
                    SystemClock.elapsedRealtime() - elapsedNowMs,
                )
                result
            } catch (error: CancellationException) {
                resetMinter()
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).w(
                    "getPoToken failed target=video elapsed=%dms type=%s",
                    SystemClock.elapsedRealtime() - elapsedNowMs,
                    error::class.simpleName,
                )
                resetMinter()
                failureCooldown.recordFailure(visitorData, cookie)
                null
            }
        }

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            pendingMints.values.toList().forEach { it.cancel() }
            pendingMints.clear()
            mutex.withLock {
                resetMinter()
                failureCooldown.reset()
            }
        }
    }

    override suspend fun invalidateAttestation() = close()

    private fun cachedResult(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        resultCache[videoId]
            ?.takeIf { it.visitorData == visitorData && it.cookie == cookie && System.currentTimeMillis() < it.expiresAtMs }
            ?.result
            ?.also { Timber.tag(TAG).d("getPoToken cache hit target=video") }

    private fun resetMinter() {
        remote.close()
        minterVisitorData = null
        minterCookie = null
        resultCache.clear()
    }

    private fun currentNetworkScope(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private data class MintKey(
        val videoId: String,
        val visitorData: String,
        val cookie: String?,
    )

    private data class CachedResult(
        val visitorData: String,
        val cookie: String?,
        val result: PoTokenResult,
        val expiresAtMs: Long,
    )

    companion object {
        private const val TAG = "AndroidTokenProvider"
        private const val TOKEN_CACHE_TTL_MS = 10L * 60L * 1000L
    }
}

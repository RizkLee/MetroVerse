/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTube as PlaybackInnerTube
import com.metrolist.innertubex.models.YouTubeLocale as PlaybackLocale
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Keeps stream extraction separate from the existing browse, playlist and upload API. */
class InnerTubeXTransport internal constructor(
    private val clientFactory: (Proxy?, String?) -> HttpClient = ::createPlaybackClient,
) {
    data class SessionKey(val transportGeneration: Long, val sessionGeneration: Long)

    class Snapshot internal constructor(
        val innerTube: PlaybackInnerTube,
        val httpClient: HttpClient,
        val generation: Long,
    ) {
        @Volatile
        internal var sessionGeneration: Long = 0L
        val sessionKey: SessionKey
            get() = SessionKey(generation, sessionGeneration)
    }

    private class Identity(
        val locale: YouTubeLocale,
        val cookie: String?,
        val visitorData: String?,
        val dataSyncId: String?,
        val authUser: String,
        val useLoginForBrowse: Boolean,
    ) {
        fun matches(other: Identity): Boolean = locale == other.locale && cookie == other.cookie &&
            visitorData == other.visitorData && dataSyncId == other.dataSyncId &&
            authUser == other.authUser && useLoginForBrowse == other.useLoginForBrowse
    }

    private var configuredIdentity: Identity? = null
    private var current: Snapshot? = null
    private var configuredProxy: Proxy? = null
    private var configuredProxyAuth: String? = null
    private var generation = 0L

    @Synchronized
    fun configure(
        locale: YouTubeLocale,
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
        authUser: String,
        useLoginForBrowse: Boolean,
        proxy: Proxy?,
        proxyAuth: String?,
    ): Snapshot {
        var snapshot = current
        if (snapshot == null || proxy != configuredProxy || proxyAuth != configuredProxyAuth) {
            val client = clientFactory(proxy, proxyAuth)
            val replacement = PlaybackInnerTube(client)
            snapshot?.innerTube?.close()
            snapshot?.httpClient?.close()
            configuredProxy = proxy
            configuredProxyAuth = proxyAuth
            snapshot = Snapshot(replacement, client, ++generation)
            current = snapshot
            configuredIdentity = null
        }
        val identity = Identity(locale, cookie, visitorData, dataSyncId, authUser, useLoginForBrowse)
        if (configuredIdentity?.matches(identity) != true) {
            // Only host identity changes replace a session. The extractor may fetch visitor data
            // internally; reapplying an unchanged null preference would discard that fresh visitor.
            snapshot.innerTube.locale = PlaybackLocale(gl = locale.gl, hl = locale.hl)
            snapshot.innerTube.replaceSession(
                cookie = cookie,
                visitorData = visitorData,
                dataSyncId = dataSyncId,
                authUser = authUser,
                useLoginForBrowse = useLoginForBrowse,
            )
            snapshot.sessionGeneration++
            configuredIdentity = identity
        }
        return snapshot
    }
}

private fun createPlaybackClient(proxy: Proxy?, proxyAuth: String?): HttpClient =
    HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            })
        }
        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(60, TimeUnit.SECONDS)
                proxy(proxy)
                if (proxyAuth != null) {
                    proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") == proxyAuth) null
                        else response.request.newBuilder().header("Proxy-Authorization", proxyAuth).build()
                    }
                }
            }
        }
    }

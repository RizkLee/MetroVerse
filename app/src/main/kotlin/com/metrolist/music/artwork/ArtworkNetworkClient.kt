/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.artwork

import com.metrolist.innertube.YouTube
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal object ArtworkNetworkClient {
    private val lock = Any()
    private var cachedProxy: Proxy? = null
    private var cachedProxyAuth: String? = null
    private var cachedClient: OkHttpClient? = null

    fun current(): OkHttpClient {
        val proxy = YouTube.proxy
        val proxyAuth = YouTube.proxyAuth
        synchronized(lock) {
            val existing = cachedClient
            if (existing != null && cachedProxy == proxy && cachedProxyAuth == proxyAuth) {
                return existing
            }
            val built =
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val urlString = request.url.toString()
                        if (!isTrustedArtworkUrl(urlString)) {
                            throw IOException("Refusing untrusted artwork host: ${request.url.host}")
                        }
                        chain.proceed(request)
                    }
                    .apply {
                        if (proxy != null) {
                            proxy(proxy)
                            if (proxyAuth != null) {
                                proxyAuthenticator { _, response ->
                                    if (response.request.header("Proxy-Authorization") != null) {
                                        null
                                    } else {
                                        response.request.newBuilder()
                                            .header("Proxy-Authorization", proxyAuth)
                                            .build()
                                    }
                                }
                            }
                        } else {
                            // Apply public-address DNS guard only on direct connections; remote proxies resolve DNS remotely.
                            dns(PublicArtworkDns)
                        }
                    }
                    .build()
            cachedProxy = proxy
            cachedProxyAuth = proxyAuth
            cachedClient = built
            return built
        }
    }
}

internal fun Response.readTextLimited(maxBytes: Int): String? {
    val body = this.body
    val contentLength = body.contentLength()
    if (contentLength > maxBytes) return null
    val source = body.source()
    val buffer = Buffer()
    var totalRead = 0L
    val chunkSize = 8192L
    while (totalRead <= maxBytes) {
        val read = source.read(buffer, minOf(chunkSize, (maxBytes + 1).toLong() - totalRead))
        if (read == -1L) break
        totalRead += read
        if (totalRead > maxBytes) {
            buffer.clear()
            return null
        }
    }
    return buffer.readUtf8()
}

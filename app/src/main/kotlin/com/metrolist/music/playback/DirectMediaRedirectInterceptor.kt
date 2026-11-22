/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Validates initial URL and redirect hops for YouTube direct media streams.
 * Never attach this interceptor to Open RSS podcast HTTP clients.
 */
object DirectMediaRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        repeat(MAX_REDIRECT_HOPS) {
            val url = request.url
            if (!isTrustedDirectMediaEndpoint(url)) {
                throw IOException("Direct media redirect is not a trusted HTTPS endpoint")
            }
            val response = chain.proceed(request)
            if (!response.isRedirect) return response
            val target = response.header("Location")?.let(url::resolve) ?: return response
            response.close()
            request = request.newBuilder().url(target).build()
        }
        throw IOException("Direct media exceeded the redirect limit")
    }

    private const val MAX_REDIRECT_HOPS = 6
}

internal fun isTrustedDirectMediaEndpoint(url: HttpUrl): Boolean =
    url.isHttps &&
        url.port == 443 &&
        url.username.isEmpty() &&
        url.password.isEmpty() &&
        (
            url.host == "googlevideo.com" ||
                url.host.endsWith(".googlevideo.com") ||
                url.host == "youtube.com" ||
                url.host.endsWith(".youtube.com")
        ) &&
        url.encodedPath == "/videoplayback"

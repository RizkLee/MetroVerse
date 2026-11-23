package com.metrolist.lastfm

import com.metrolist.lastfm.models.Authentication
import com.metrolist.lastfm.models.LastFmError
import com.metrolist.lastfm.models.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

object LastFM {
    var sessionKey: String? = null

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest { url("https://ws.audioscrobbler.com/2.0/") }
            expectSuccess = false
        }
    }

    private fun Map<String, String>.apiSig(secret: String): String {
        val sorted = toSortedMap()
        val toHash = sorted.entries.joinToString("") { it.key + it.value } + secret
        val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun HttpRequestBuilder.lastfmParams(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
        format: String = "json"
    ) {
        contentType(ContentType.Application.FormUrlEncoded)
        userAgent("MetroVerse (https://github.com/RizkLee/MetroVerse)")
        val paramsForSig = mutableMapOf(
            "method" to method,
            "api_key" to apiKey
        ).apply {
            sessionKey?.let { put("sk", it) }
            putAll(extra)
        }
        val apiSig = paramsForSig.apiSig(secret)
        setBody(FormDataContent(Parameters.build {
            paramsForSig.forEach { (k, v) -> append(k, v) }
            append("api_sig", apiSig)
            append("format", format)
        }))
    }

    private fun requireInitialized() {
        if (!isInitialized()) {
            throw LastFmException(10, "Last.fm API key or secret is not configured")
        }
    }

    private fun requireSessionKey(): String =
        sessionKey?.takeIf { it.isNotBlank() }
            ?: throw LastFmException(9, "Last.fm session key is missing")

    private suspend fun parseAndValidateResponse(response: HttpResponse): String {
        val responseText = response.bodyAsText()
        val parsed = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrNull()
        val errorElement = parsed?.get("error")
        if (errorElement != null) {
            val errorCode = errorElement.jsonPrimitive.content.toIntOrNull() ?: response.status.value
            val errorMessage = parsed["message"]?.jsonPrimitive?.content ?: response.status.description
            throw LastFmException(errorCode, errorMessage)
        }
        if (!response.status.isSuccess()) {
            throw LastFmException(
                response.status.value,
                responseText.ifBlank { response.status.description },
            )
        }
        return responseText
    }

    // OAuth methods (kept for backward compatibility)
    suspend fun getToken() = runCatching {
        requireInitialized()
        val response = client.post {
            lastfmParams(
                method = "auth.getToken",
                apiKey = API_KEY,
                secret = SECRET
            )
        }
        json.decodeFromString<TokenResponse>(parseAndValidateResponse(response))
    }

    suspend fun getSession(token: String) = runCatching {
        requireInitialized()
        val response = client.post {
            lastfmParams(
                method = "auth.getSession",
                apiKey = API_KEY,
                secret = SECRET,
                extra = mapOf("token" to token)
            )
        }
        json.decodeFromString<Authentication>(parseAndValidateResponse(response))
    }

    fun getAuthUrl(token: String): String {
        return "https://www.last.fm/api/auth/?api_key=$API_KEY&token=$token"
    }

    // Mobile session authentication
    suspend fun getMobileSession(username: String, password: String) = runCatching {
        requireInitialized()
        val response = client.post {
            lastfmParams(
                method = "auth.getMobileSession",
                apiKey = API_KEY,
                secret = SECRET,
                extra = mapOf("username" to username, "password" to password)
            )
        }
        json.decodeFromString<Authentication>(parseAndValidateResponse(response))
    }

    class LastFmException(val code: Int, override val message: String) : Exception(message) {
        override fun toString(): String = "LastFmException(code=$code, message=$message)"
    }

    suspend fun updateNowPlaying(
        artist: String, track: String,
        album: String? = null, trackNumber: Int? = null, duration: Int? = null
    ) = runCatching {
        requireInitialized()
        val sk = requireSessionKey()
        val response = client.post {
            lastfmParams(
                method = "track.updateNowPlaying",
                apiKey = API_KEY,
                secret = SECRET,
                sessionKey = sk,
                extra = buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                    trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", it.toString()) }
                    duration?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
                }
            )
        }
        parseAndValidateResponse(response)
        Unit
    }

    suspend fun scrobble(
        artist: String, track: String, timestamp: Long,
        album: String? = null, trackNumber: Int? = null, duration: Int? = null
    ) = runCatching {
        requireInitialized()
        val sk = requireSessionKey()
        val response = client.post {
            lastfmParams(
                method = "track.scrobble",
                apiKey = API_KEY,
                secret = SECRET,
                sessionKey = sk,
                extra = buildMap {
                    put("artist[0]", artist)
                    put("track[0]", track)
                    put("timestamp[0]", timestamp.toString())
                    album?.takeIf { it.isNotBlank() }?.let { put("album[0]", it) }
                    trackNumber?.takeIf { it > 0 }?.let { put("trackNumber[0]", it.toString()) }
                    duration?.takeIf { it > 0 }?.let { put("duration[0]", it.toString()) }
                }
            )
        }
        parseAndValidateResponse(response)
        Unit
    }

    suspend fun setLoveStatus(
        artist: String, track: String, love: Boolean
    ) = runCatching {
        requireInitialized()
        val sk = requireSessionKey()
        val method = if (love) "track.love" else "track.unlove"
        val response = client.post {
            lastfmParams(
                method = method,
                apiKey = API_KEY,
                secret = SECRET,
                sessionKey = sk,
                extra = buildMap {
                    put("artist", artist)
                    put("track", track)
                }
            )
        }
        parseAndValidateResponse(response)
        Unit
    }

    // API keys passed from the app module (loaded from BuildConfig/GitHub Secrets)
    private var API_KEY = ""
    private var SECRET = ""

    /**
     * Initialize LastFM with API credentials
     * @param apiKey LastFM API key
     * @param secret LastFM secret key
     */
    fun initialize(apiKey: String, secret: String) {
        API_KEY = apiKey
        SECRET = secret
    }

    fun isInitialized(): Boolean = API_KEY.isNotEmpty() && SECRET.isNotEmpty()

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
}

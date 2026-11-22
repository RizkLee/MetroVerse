/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.potoken

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class PoTokenService : Service() {
    private val sessions = mutableMapOf<String, Session>()

    override fun onCreate() {
        super.onCreate()
        liveInstances++
    }

    override fun onBind(intent: Intent): IBinder =
        Session().also { sessions[requireNotNull(intent.action)] = it }.messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        sessions.remove(intent.action)?.close()
        return false
    }

    override fun onDestroy() {
        sessions.values.forEach(Session::close)
        sessions.clear()
        super.onDestroy()
        liveInstances--
        // Chromium cannot unload; exit this bound-only process instead of retaining roughly 130 MB idle.
        Handler(Looper.getMainLooper()).post { if (liveInstances == 0) Process.killProcess(Process.myPid()) }
    }

    private companion object {
        var liveInstances = 0
    }

    private inner class Session {
        private val scope = MainScope()
        private var prewarmedWebView =
            try {
                WebView(applicationContext)
            } catch (error: Exception) {
                Timber.tag("PoTokenService").d("WebView prewarm failed type=%s", error::class.simpleName)
                null
            }
        private var minter: BotGuardPoTokenMinter? = null
        private var visitorData: String? = null
        private var cookie: String? = null
        private var proxyConfig: PoTokenProxyConfig? = null
        private var playerRequestToken: String? = null
        private var busy = false
        val messenger = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))

        private fun handleMessage(message: Message): Boolean {
            if (message.what != PoTokenIpc.MINT) return false
            val replyTo = message.replyTo ?: return true
            val requestId = message.arg1
            val data = message.data
            val requestedVisitor = data.getString(PoTokenIpc.VISITOR_DATA) ?: return true
            val videoId = data.getString(PoTokenIpc.VIDEO_ID) ?: return true
            if (busy) return true
            busy = true
            scope.launch {
                val response = Bundle()
                try {
                    val requestedCookie = data.getString(PoTokenIpc.COOKIE)
                    val requestedProxy =
                        PoTokenProxyConfig(
                            typeName = data.getString(PoTokenIpc.PROXY_TYPE),
                            host = data.getString(PoTokenIpc.PROXY_HOST),
                            port = data.getInt(PoTokenIpc.PROXY_PORT, 0),
                            auth = data.getString(PoTokenIpc.PROXY_AUTH),
                        )
                    if (
                        minter?.let { it.isDead || it.isExpired } != false ||
                        visitorData != requestedVisitor ||
                        cookie != requestedCookie ||
                        proxyConfig != requestedProxy
                    ) {
                        minter?.close()
                        minter = null
                        playerRequestToken = null
                        visitorData = requestedVisitor
                        cookie = requestedCookie
                        proxyConfig = requestedProxy
                        val webView = prewarmedWebView
                        prewarmedWebView = null
                        minter =
                            BotGuardPoTokenMinter.create(
                                context = this@PoTokenService,
                                visitorData = requestedVisitor,
                                cookie = requestedCookie,
                                videoId = videoId,
                                proxyConfig = requestedProxy,
                                prewarmedWebView = webView,
                            )
                    }
                    val activeMinter = requireNotNull(minter)
                    val sessionToken = playerRequestToken ?: activeMinter.generate(requestedVisitor).also { playerRequestToken = it }
                    val videoToken = activeMinter.generate(videoId)
                    response.putString(PoTokenIpc.PLAYER_TOKEN, sessionToken)
                    response.putString(PoTokenIpc.VIDEO_TOKEN, videoToken)
                    response.putString(PoTokenIpc.VISITOR_DATA, requestedVisitor)
                    response.putLong(PoTokenIpc.EXPIRES_AT, activeMinter.expiresAtMs)
                } catch (error: CancellationException) {
                    if (!scope.isActive) throw error
                    resetMinter()
                } catch (_: Exception) {
                    resetMinter()
                } finally {
                    busy = false
                }
                runCatching {
                    replyTo.send(Message.obtain(null, PoTokenIpc.MINT, requestId, 0).apply { this.data = response })
                }
            }
            return true
        }

        private fun resetMinter() {
            minter?.close()
            minter = null
            playerRequestToken = null
            visitorData = null
            cookie = null
            proxyConfig = null
        }

        fun close() {
            prewarmedWebView?.destroy()
            prewarmedWebView = null
            scope.cancel()
            resetMinter()
        }
    }
}

internal object PoTokenIpc {
    const val MINT = 1
    const val VIDEO_ID = "videoId"
    const val VISITOR_DATA = "visitorData"
    const val COOKIE = "cookie"
    const val PROXY_TYPE = "proxyType"
    const val PROXY_HOST = "proxyHost"
    const val PROXY_PORT = "proxyPort"
    const val PROXY_AUTH = "proxyAuth"
    const val PLAYER_TOKEN = "playerRequestToken"
    const val VIDEO_TOKEN = "streamingDataToken"
    const val EXPIRES_AT = "expiresAtMs"
}

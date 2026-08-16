/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ListenTogetherServer(
    val name: String,
    val url: String,
    val location: String,
    val operator: String
)

internal fun normalizeListenTogetherServerUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.contains("metroserver.meowery.eu", ignoreCase = true)) {
        ListenTogetherServers.defaultServerUrl
    } else {
        trimmed
    }
}

object ListenTogetherServers {
    private const val ServersJson = """
        [
          {
            "name": "Metrolist official",
            "url": "wss://metroserverx.meowery.eu/ws",
            "location": "Poland",
            "operator": "Metrolist"
          }
        ]
    """

    private val json = Json { ignoreUnknownKeys = true }

    val servers: List<ListenTogetherServer> by lazy {
        json.decodeFromString(ServersJson)
    }

    val defaultServerUrl: String
        get() = servers.first().url

    fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }
}

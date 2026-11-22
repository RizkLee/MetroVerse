/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.hasSupportedYouTubeAuthCookie
import com.metrolist.innertube.utils.sha1
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

private const val CLIENT_HEALTH_SCHEMA = 2

@Serializable
data class ClientHealth(
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastFailure: ClientFailureKind? = null,
    val lastFailureAtMs: Long? = null,
    val cooldownUntilMs: Long? = null,
)

class ClientHealthTracker(
    private val preferences: SharedPreferences? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val accountFingerprint: () -> String = { "${YouTube.dataSyncId.orEmpty()}|${hasSupportedYouTubeAuthCookie(YouTube.cookie.orEmpty())}" },
    private val networkScopeProvider: () -> String = { "default" },
    private val persistenceEnabled: Boolean = preferences != null,
) : ClientHealthMonitor {

    constructor(context: Context) : this(
        preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        networkScopeProvider = { resolveNetworkScope(context.applicationContext) },
        persistenceEnabled = true,
    )

    private data class HealthKey(
        val clientId: String,
        val scope: ClientHealthScope?,
        val account: String,
        val network: String,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val state = MutableStateFlow(if (persistenceEnabled) load() else emptyMap())

    private fun key(
        clientId: String,
        scope: ClientHealthScope?,
    ): HealthKey = HealthKey(clientId, scope, sha1(accountFingerprint()).take(32), networkScopeProvider())

    fun health(
        clientId: String,
        scope: ClientHealthScope? = null,
    ): ClientHealth = state.value[key(clientId, scope)] ?: ClientHealth()

    override fun scoreAdjustment(
        clientId: String,
        scope: ClientHealthScope?,
    ): Int = healthAdjustment(health(clientId)) + (scope?.let { healthAdjustment(health(clientId, it)) } ?: 0)

    private fun healthAdjustment(health: ClientHealth): Int {
        val successBonus = min(health.successes, MAX_SUCCESS_BONUS_COUNT) * SUCCESS_BONUS
        val failurePenalty = min(health.consecutiveFailures, MAX_CONSECUTIVE_FAILURES) * FAILURE_PENALTY
        val cooldownPenalty = if ((health.cooldownUntilMs ?: 0L) > nowMs()) COOLDOWN_PENALTY else 0
        return successBonus - failurePenalty - cooldownPenalty
    }

    fun clientsInCooldown(scope: ClientHealthScope): Set<String> {
        val current = key("", scope)
        val now = nowMs()
        return state.value.entries
            .filter { (key, health) ->
                key.account == current.account &&
                    key.network == current.network &&
                    (key.scope == null || key.scope == scope) &&
                    (health.cooldownUntilMs ?: 0L) > now &&
                    (health.lastFailure == ClientFailureKind.PLAYABILITY || health.lastFailure == ClientFailureKind.PLAYER_REQUEST)
            }
            .mapTo(mutableSetOf()) { it.key.clientId }
    }

    override fun recordSuccess(
        clientId: String,
        scope: ClientHealthScope?,
    ) {
        synchronized(lock) {
            val healthByClient = if (persistenceEnabled) load() else state.value
            val key = key(clientId, scope)
            val current = healthByClient[key] ?: ClientHealth()
            state.value =
                (healthByClient - key) +
                (
                    key to
                        current.copy(
                            successes = current.successes + 1,
                            consecutiveFailures = 0,
                            cooldownUntilMs = null,
                        )
                )
            if (persistenceEnabled) persistLocked()
        }
    }

    override fun recordFailure(
        clientId: String,
        kind: ClientFailureKind,
        scope: ClientHealthScope?,
    ) {
        val timestamp = nowMs()
        synchronized(lock) {
            val healthByClient = if (persistenceEnabled) load() else state.value
            val key = key(clientId, scope)
            val current = healthByClient[key] ?: ClientHealth()
            val consecutive = current.consecutiveFailures + 1
            val cooldownMs =
                when {
                    kind == ClientFailureKind.MEDIA_FORBIDDEN -> FORBIDDEN_COOLDOWN_MS
                    consecutive >= FAILURES_BEFORE_COOLDOWN -> DEFAULT_COOLDOWN_MS
                    else -> 0L
                }
            state.value =
                (healthByClient - key) +
                (
                    key to
                        current.copy(
                            failures = current.failures + 1,
                            consecutiveFailures = consecutive,
                            lastFailure = kind,
                            lastFailureAtMs = timestamp,
                            cooldownUntilMs = (timestamp + cooldownMs).takeIf { cooldownMs > 0L },
                        )
                )
            if (persistenceEnabled) persistLocked()
        }
    }

    /** Record failure using a stream clientName or profileId resolved via PlaybackClientCatalog. */
    fun recordPlaybackFailure(
        clientOrProfileId: String?,
        kind: ClientFailureKind,
        hints: ContentHints,
        authenticated: Boolean,
    ) {
        val manifestId =
            PlaybackClientCatalog.manifestIdFromProfileId(clientOrProfileId)
                ?: clientOrProfileId?.takeIf { it.isNotBlank() }
                ?: return
        recordFailure(manifestId, kind, ClientHealthScope.from(hints, authenticated))
    }

    fun recordPlaybackSuccess(
        clientOrProfileId: String?,
        hints: ContentHints,
        authenticated: Boolean,
    ) {
        val manifestId =
            PlaybackClientCatalog.manifestIdFromProfileId(clientOrProfileId)
                ?: clientOrProfileId?.takeIf { it.isNotBlank() }
                ?: return
        recordSuccess(manifestId, ClientHealthScope.from(hints, authenticated))
    }

    fun reset(
        clientId: String? = null,
        scope: ClientHealthScope? = null,
    ) {
        synchronized(lock) {
            val persisted = if (persistenceEnabled) load() else state.value
            state.value =
                when {
                    clientId == null -> emptyMap()
                    scope == null -> persisted.filterKeys { it.clientId != clientId }
                    else -> persisted - key(clientId, scope)
                }
            if (persistenceEnabled) persistLocked()
        }
    }

    private fun load(): Map<HealthKey, ClientHealth> {
        val raw = preferences?.getString(KEY_SNAPSHOT, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            json
                .decodeFromString<HealthSnapshot>(raw)
                .takeIf { it.schema == CLIENT_HEALTH_SCHEMA }
                ?.entries
                .orEmpty()
                .takeLast(MAX_PERSISTED_ENTRIES)
                .associate { HealthKey(it.clientId, it.scope, it.account, it.network) to it.health }
        }.getOrDefault(emptyMap())
    }

    private fun persistLocked() {
        val encoded =
            json.encodeToString(
                HealthSnapshot(
                    entries =
                        state.value.entries.toList().takeLast(MAX_PERSISTED_ENTRIES).map { entry ->
                            val k = entry.key
                            PersistedHealth(k.clientId, k.scope, k.account, k.network, entry.value)
                        },
                ),
            )
        preferences?.edit { putString(KEY_SNAPSHOT, encoded) }
    }

    companion object {
        private const val PREFS_NAME = "client_health_v2"
        private const val KEY_SNAPSHOT = "snapshot"
        const val MAX_SUCCESS_BONUS_COUNT = 5
        const val SUCCESS_BONUS = 2
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val FAILURE_PENALTY = 18
        const val COOLDOWN_PENALTY = 1_000
        const val FAILURES_BEFORE_COOLDOWN = 2
        const val DEFAULT_COOLDOWN_MS = 2 * 60 * 1000L
        const val FORBIDDEN_COOLDOWN_MS = 10 * 60 * 1000L
        const val MAX_PERSISTED_ENTRIES = 128

        private fun resolveNetworkScope(context: Context): String {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown:offline"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
            val transport =
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            return "$transport:${if (validated) "validated" else "offline"}"
        }
    }
}

@Serializable
private data class HealthSnapshot(
    val schema: Int = CLIENT_HEALTH_SCHEMA,
    val entries: List<PersistedHealth> = emptyList(),
)

@Serializable
private data class PersistedHealth(
    val clientId: String,
    val scope: ClientHealthScope?,
    val account: String,
    val network: String,
    val health: ClientHealth,
)

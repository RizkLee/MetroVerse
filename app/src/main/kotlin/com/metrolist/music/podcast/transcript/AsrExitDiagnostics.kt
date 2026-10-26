/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.metrolist.music.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AsrExitKind { MEMORY, NATIVE_CRASH, CRASH, NOT_RESPONDING, UNKNOWN }

data class AsrExitNotice(val token: String, val model: AsrModel, val kind: AsrExitKind) {
    val canRecommendTiny: Boolean
        get() = kind == AsrExitKind.MEMORY && model.variant == AsrModelVariant.SMALL_STREAMING &&
            AsrModelVariant.TINY_STREAMING in model.language.supportedModelVariants
}

internal data class AsrExitSession(
    val token: String,
    val processToken: String,
    val pid: Int,
    val processName: String,
    val startedAtMs: Long,
    val model: AsrModel,
    val versionCode: Int,
)

internal data class AsrExitRecord(val pid: Int, val processName: String, val timestampMs: Long, val reason: Int, val sessionToken: String?)

internal fun asrExitNotice(session: AsrExitSession, records: List<AsrExitRecord>, nowMs: Long): AsrExitNotice? {
    val exit = records.filter {
        it.sessionToken == session.token && it.pid == session.pid && it.processName == session.processName &&
            it.timestampMs >= session.startedAtMs && it.timestampMs <= nowMs
    }.maxByOrNull { it.timestampMs } ?: return null
    val kind = when (exit.reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> AsrExitKind.MEMORY
        ApplicationExitInfo.REASON_CRASH_NATIVE -> AsrExitKind.NATIVE_CRASH
        ApplicationExitInfo.REASON_CRASH -> AsrExitKind.CRASH
        ApplicationExitInfo.REASON_ANR -> AsrExitKind.NOT_RESPONDING
        ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> AsrExitKind.UNKNOWN
        else -> return null
    }
    return AsrExitNotice(session.token, session.model, kind)
}

/** One overwritten record, containing no media URLs, text, audio or growing log history. */
@SuppressLint("ApplySharedPref", "UseKtx") // Off-main commit results confirm durable recording and consumption.
internal class AsrExitStore(private val preferences: SharedPreferences) {
    val hasPendingSession: Boolean get() = preferences.contains("token")
    fun write(session: AsrExitSession): Boolean = preferences.edit().clear()
        .putString("token", session.token).putString("processToken", session.processToken)
        .putInt("pid", session.pid).putString("processName", session.processName)
        .putLong("startedAtMs", session.startedAtMs).putString("language", session.model.language.code)
        .putString("variant", session.model.variant.pathName).putInt("version", session.versionCode).commit()

    fun read(): AsrExitSession? = try {
        val token = preferences.getString("token", null)
        val processToken = preferences.getString("processToken", null)
        val processName = preferences.getString("processName", null)
        val language = AsrLanguage.fromCode(preferences.getString("language", null))
        val variant = AsrModelVariant.entries.firstOrNull { it.pathName == preferences.getString("variant", null) }
        val pid = preferences.getInt("pid", 0)
        val started = preferences.getLong("startedAtMs", 0L)
        if (token.isNullOrBlank() || processToken.isNullOrBlank() || processName.isNullOrBlank() ||
            token.length > 64 || processToken.length > 64 || processName.length > 256 ||
            language == null || variant !in language.supportedModelVariants || pid <= 0 || started <= 0L
        ) null else AsrExitSession(token, processToken, pid, processName, started, language.model(checkNotNull(variant)), preferences.getInt("version", 0))
    } catch (_: Exception) { null }

    fun clear(token: String): Boolean =
        preferences.getString("token", null) == token && preferences.edit().clear().commit()

    fun consumePrevious(processToken: String, nowMs: Long, history: (AsrExitSession) -> List<AsrExitRecord>): AsrExitNotice? {
        if (!preferences.contains("token")) return null
        val session = read() ?: run { preferences.edit().clear().commit(); return null }
        if (session.processToken == processToken) return null
        val notice = try { asrExitNotice(session, history(session), nowMs) } catch (_: Exception) { null }
        // Publish only after durable consumption, so a failed write cannot repeat a popup.
        return if (clear(session.token)) notice else null
    }
}

@Singleton
class AsrExitDiagnostics @Inject constructor(@ApplicationContext private val context: Context) {
    private val processToken by lazy { UUID.randomUUID().toString() }
    private val store by lazy { AsrExitStore(context.getSharedPreferences("podcast_asr_exit_state", Context.MODE_PRIVATE)) }
    private val mutex = Mutex()
    private var summaryOwner: String? = null
    private val _notice = MutableStateFlow<AsrExitNotice?>(null)
    val notice = _notice.asStateFlow()

    suspend fun inspectPreviousExit() {
        if (Build.VERSION.SDK_INT < 30) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try { inspectLocked() } catch (_: Exception) { /* Diagnostics must not prevent startup. */ }
            }
        }
    }

    fun dismiss(token: String) {
        val current = _notice.value ?: return
        if (current.token == token) _notice.compareAndSet(current, null)
    }

    suspend fun <T> track(model: AsrModel, block: suspend () -> T): T {
        if (Build.VERSION.SDK_INT < 30) return block()
        var session: AsrExitSession? = null
        try {
            try {
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        inspectLocked()
                        val candidate = AsrExitSession(UUID.randomUUID().toString(), processToken, Process.myPid(),
                            Api30.processName(), System.currentTimeMillis(), model, BuildConfig.VERSION_CODE)
                        // Assign inside IO: cancellation on dispatcher return must still clear our marker.
                        session = candidate
                        if (store.write(candidate)) {
                            Api30.writeSummary(context, candidate.token)
                            summaryOwner = candidate.token
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Recording is best effort; recognition still works. */ }
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            session?.let { owned ->
                withContext(NonCancellable + Dispatchers.IO) {
                    mutex.withLock {
                        if (summaryOwner == owned.token) {
                            try { Api30.writeSummary(context, null) } catch (_: Exception) { }
                            summaryOwner = null
                        }
                        try { store.clear(owned.token) } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    @RequiresApi(30)
    private fun inspectLocked() {
        if (!store.hasPendingSession) return
        store.consumePrevious(processToken, System.currentTimeMillis()) { Api30.history(context, it.pid) }
            ?.let { _notice.value = it }
    }

    @RequiresApi(30)
    private object Api30 {
        private const val SUMMARY_PREFIX = "metroverse-asr:"
        // API 37.2 added this public reason after our compile SDK 37.0. Unknown
        // signals remain unknown; only the platform's exact constant is trusted.
        private val memoryLimiterReason: Int? by lazy {
            if (Build.VERSION.SDK_INT < 37) null else try {
                ApplicationExitInfo::class.java.getField("REASON_MEMORY_LIMITER").getInt(null)
            } catch (_: ReflectiveOperationException) { null }
        }

        fun processName(): String = Application.getProcessName()

        fun writeSummary(context: Context, token: String?) {
            val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))
            manager.setProcessStateSummary(token?.let { (SUMMARY_PREFIX + it).toByteArray(Charsets.UTF_8) })
        }

        fun history(context: Context, pid: Int): List<AsrExitRecord> {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return manager.getHistoricalProcessExitReasons(context.packageName, pid, 5).map { exit ->
                val reason = if (memoryLimiterReason == exit.reason) ApplicationExitInfo.REASON_LOW_MEMORY else exit.reason
                val token = exit.processStateSummary?.takeIf { it.size <= 128 }?.toString(Charsets.UTF_8)
                    ?.takeIf { it.startsWith(SUMMARY_PREFIX) }?.removePrefix(SUMMARY_PREFIX)
                AsrExitRecord(exit.pid, exit.processName, exit.timestamp, reason, token)
            }
        }
    }
}

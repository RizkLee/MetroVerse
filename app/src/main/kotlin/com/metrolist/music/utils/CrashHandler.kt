/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import com.metrolist.music.BuildConfig
import com.metrolist.music.ui.screens.CrashActivity
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class CrashHandler private constructor(
    private val applicationContext: Context,
) : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()
    private val handling = AtomicBoolean(false)

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        if (!handling.compareAndSet(false, true)) {
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }
        try {
            val report =
                CrashReport.create(
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    deviceInfo = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL} / ${thread.name}",
                    error = throwable,
                )
            Timber.e(throwable, "App crashed")
            runCatching { store(applicationContext).save(report) }

            val intent =
                Intent(applicationContext, CrashActivity::class.java).apply {
                    putExtra(EXTRA_CRASH_ID, report.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            applicationContext.startActivity(intent)

            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        } catch (e: Throwable) {
            Timber.e(e, "Error handling crash")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val EXTRA_CRASH_LOG = "crash_log"
        const val EXTRA_CRASH_ID = "crash_id"

        fun store(context: Context): FileCrashStore =
            FileCrashStore(File(context.applicationContext.filesDir, "crashes"))

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Timber.d("CrashHandler installed")
        }
    }
}

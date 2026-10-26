/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AsrExitDiagnosticsTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private val model = AsrLanguage.ENGLISH.model(AsrModelVariant.SMALL_STREAMING)
    private val session = AsrExitSession("attempt", "old-process", 42, "com.example", 100L, model, 19)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        preferences = context.getSharedPreferences("podcast_asr_exit_state", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        ShadowProcess.setPid(42)
        ShadowProcess.setProcessName(context.packageName)
    }

    @Test
    fun stateIsOneOverwrittenRecordAndCleanupCannotClearANewerOwner() {
        val store = AsrExitStore(preferences)
        repeat(100) { index ->
            assertTrue(store.write(session.copy(token = "attempt-$index")))
            assertEquals(8, preferences.all.size)
        }
        val newer = session.copy(token = "newer")
        store.write(newer)
        store.clear("attempt-99")
        assertEquals(newer, store.read())
        store.clear(newer.token)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun aPreviousSessionIsConsumedOnceButTheCurrentProcessIsLeftAlone() {
        val store = AsrExitStore(preferences)
        store.write(session)
        var queries = 0
        val history: (AsrExitSession) -> List<AsrExitRecord> = {
            queries++
            listOf(AsrExitRecord(it.pid, it.processName, 200L, ApplicationExitInfo.REASON_LOW_MEMORY, it.token))
        }
        assertNull(store.consumePrevious("old-process", 1_000L, history))
        assertEquals(0, queries)
        assertNotNull(store.consumePrevious("new-process", 1_000L, history))
        assertNull(store.consumePrevious("new-process", 1_000L, history))
        assertEquals(1, queries)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun manualStopsMissingRecordsAndUnavailableHistoryLeaveNoRepeatedPopup() {
        val store = AsrExitStore(preferences)
        store.write(session)
        assertNull(store.consumePrevious("new", 1_000L) {
            listOf(AsrExitRecord(it.pid, it.processName, 200L, ApplicationExitInfo.REASON_USER_REQUESTED, it.token))
        })
        assertTrue(preferences.all.isEmpty())
        store.write(session)
        assertNull(store.consumePrevious("new", 1_000L) { emptyList() })
        assertTrue(preferences.all.isEmpty())
        store.write(session)
        assertNull(store.consumePrevious("new", 1_000L) { throw IllegalStateException("Unavailable") })
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun failedConsumptionDoesNotPublishUntilTheRecordIsDurablyCleared() {
        AsrExitStore(preferences).write(session)
        val failedPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by preferences.edit() {
                override fun clear(): SharedPreferences.Editor = this
                override fun commit(): Boolean = false
            }
        }
        val history: (AsrExitSession) -> List<AsrExitRecord> = {
            listOf(AsrExitRecord(it.pid, it.processName, 200L, ApplicationExitInfo.REASON_LOW_MEMORY, it.token))
        }
        assertNull(AsrExitStore(failedPreferences).consumePrevious("new", 1_000L, history))
        assertEquals(session, AsrExitStore(preferences).read())
        assertNotNull(AsrExitStore(preferences).consumePrevious("new", 1_000L, history))
        assertNull(AsrExitStore(preferences).consumePrevious("new", 1_000L, history))
    }

    @Test
    fun systemHistorySummaryIdentifiesTheRecognitionAttemptAndDismissesOnce() = runBlocking {
        val recorded = session.copy(processName = context.packageName)
        AsrExitStore(preferences).write(recorded)
        addSystemExit(recorded, recorded.token)
        val subject = AsrExitDiagnostics(context)
        subject.inspectPreviousExit()
        assertEquals(AsrExitNotice(recorded.token, model, AsrExitKind.MEMORY), subject.notice.value)
        assertTrue(subject.notice.value!!.canRecommendTiny)
        subject.dismiss("unrelated")
        assertNotNull(subject.notice.value)
        subject.dismiss(recorded.token)
        subject.inspectPreviousExit()
        assertNull(subject.notice.value)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun anUnrelatedSystemSummaryCannotTurnAnOldMarkerIntoAMemoryWarning() = runBlocking {
        val recorded = session.copy(processName = context.packageName)
        AsrExitStore(preferences).write(recorded)
        addSystemExit(recorded, "other-attempt")
        val subject = AsrExitDiagnostics(context)
        subject.inspectPreviousExit()
        assertNull(subject.notice.value)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun corruptStateIsDiscardedWithoutReadingSystemHistory() {
        preferences.edit().putBoolean("token", true).commit()
        val store = AsrExitStore(preferences)
        assertNull(store.consumePrevious("new", 1_000L) { error("Must not query corrupt state") })
        assertTrue(preferences.all.isEmpty())
        store.write(session)
        preferences.edit().putString("language", "zh").commit()
        assertNull(store.consumePrevious("new", 1_000L) { error("Unsupported model must be rejected") })
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun normalRecognitionWritesOnlyAtItsBoundariesAndEmptyStartupDoesNotWrite() = runBlocking {
        val writes = AtomicInteger()
        val subject = AsrExitDiagnostics(countedContext { writes.incrementAndGet() })
        subject.inspectPreviousExit()
        assertEquals(0, writes.get())
        val result = subject.track(model) {
            assertEquals(1, writes.get())
            assertEquals(model, AsrExitStore(preferences).read()!!.model)
            "finished"
        }
        assertEquals("finished", result)
        assertEquals(2, writes.get())
        assertTrue(preferences.all.isEmpty())
        subject.inspectPreviousExit()
        assertEquals(2, writes.get())
    }

    @Test
    fun handledFailureAndCancellationBothRemoveTheActiveMarker() = runBlocking {
        val subject = AsrExitDiagnostics(context)
        try {
            subject.track(model) { throw IllegalStateException("Recognition failed") }
        } catch (expected: IllegalStateException) { assertEquals("Recognition failed", expected.message) }
        assertTrue(preferences.all.isEmpty())
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val task = launch {
                subject.track(model) { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()
            assertNotNull(AsrExitStore(preferences).read())
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertTrue(preferences.all.isEmpty())
        }
    }

    @Test
    fun cancellationDuringMarkerCommitCannotOrphanAFalseCrashRecord() = runBlocking {
        val writes = AtomicInteger()
        lateinit var task: Job
        val subject = AsrExitDiagnostics(countedContext { if (writes.incrementAndGet() == 1) task.cancel() })
        var recognitionEntered = false
        task = launch(start = CoroutineStart.LAZY) { subject.track(model) { recognitionEntered = true } }
        task.start()
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(recognitionEntered)
        assertEquals(2, writes.get())
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun olderAndroidRunsRecognitionWithoutCreatingDiagnosticState() = runBlocking {
        val writes = AtomicInteger()
        val subject = AsrExitDiagnostics(countedContext { writes.incrementAndGet() })
        subject.inspectPreviousExit()
        assertEquals("finished", subject.track(model) { "finished" })
        assertEquals(0, writes.get())
        assertTrue(preferences.all.isEmpty())
    }

    private fun countedContext(onCommit: () -> Unit): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor = CountingEditor(preferences.edit(), onCommit)
        }
    }

    private fun addSystemExit(recorded: AsrExitSession, summaryToken: String) {
        val exit = ApplicationExitInfoBuilder.newBuilder()
            .setPid(recorded.pid).setProcessName(recorded.processName).setTimestamp(200L)
            .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
            .setProcessStateSummary("metroverse-asr:$summaryToken".toByteArray(Charsets.UTF_8)).build()
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(exit)
    }

    private class CountingEditor(private val base: SharedPreferences.Editor, private val onCommit: () -> Unit) : SharedPreferences.Editor by base {
        override fun clear(): SharedPreferences.Editor { base.clear(); return this }
        override fun putString(key: String, value: String?): SharedPreferences.Editor { base.putString(key, value); return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { base.putInt(key, value); return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { base.putLong(key, value); return this }
        override fun commit(): Boolean = base.commit().also { onCommit() }
    }
}

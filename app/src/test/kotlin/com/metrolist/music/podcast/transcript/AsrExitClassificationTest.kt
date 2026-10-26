/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrExitClassificationTest {
    private val session = AsrExitSession("attempt", "old-process", 42, "com.example", 100L,
        AsrLanguage.ENGLISH.model(AsrModelVariant.SMALL_STREAMING), 19)

    private fun record(reason: Int, timestamp: Long = 200L) =
        AsrExitRecord(session.pid, session.processName, timestamp, reason, session.token)

    @Test
    fun onlyConfirmedMemoryExitSuggestsTinyAndAlreadyTinyDoesNotSuggestItAgain() {
        val records = listOf(record(ApplicationExitInfo.REASON_LOW_MEMORY))
        val notice = asrExitNotice(session, records, 1_000L)!!
        assertEquals(AsrExitKind.MEMORY, notice.kind)
        assertTrue(notice.canRecommendTiny)
        val tinySession = session.copy(model = AsrLanguage.ENGLISH.model(AsrModelVariant.TINY_STREAMING))
        assertFalse(asrExitNotice(tinySession, records, 1_000L)!!.canRecommendTiny)
    }

    @Test
    fun aSignalOrUnspecifiedResourceLimitNeverBecomesAMemoryDiagnosis() {
        listOf(ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE).forEach { reason ->
            val notice = asrExitNotice(session, listOf(record(reason)), 1_000L)!!
            assertEquals(AsrExitKind.UNKNOWN, notice.kind)
            assertFalse(notice.canRecommendTiny)
        }
    }

    @Test
    fun systemRecordedCrashesAndAnrsKeepTheirOwnMeaning() {
        mapOf(ApplicationExitInfo.REASON_CRASH_NATIVE to AsrExitKind.NATIVE_CRASH,
            ApplicationExitInfo.REASON_CRASH to AsrExitKind.CRASH,
            ApplicationExitInfo.REASON_ANR to AsrExitKind.NOT_RESPONDING).forEach { (reason, kind) ->
            assertEquals(kind, asrExitNotice(session, listOf(record(reason)), 1_000L)!!.kind)
        }
    }

    @Test
    fun intentionalStopsAndPackageChangesDoNotProduceADiagnosticPopup() {
        listOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_OTHER).forEach { reason ->
            assertNull(asrExitNotice(session, listOf(record(reason)), 1_000L))
        }
    }

    @Test
    fun exactSessionIdentityRejectsPidReuseOtherProcessesAndMissingSystemSummaries() {
        val valid = record(ApplicationExitInfo.REASON_LOW_MEMORY)
        listOf(valid.copy(pid = 43), valid.copy(processName = "com.example:crash"),
            valid.copy(timestampMs = 99L), valid.copy(timestampMs = 1_001L),
            valid.copy(sessionToken = "another-attempt"), valid.copy(sessionToken = null)).forEach {
            assertNull(asrExitNotice(session, listOf(it), 1_000L))
        }
        assertNull(asrExitNotice(session, emptyList(), 1_000L))
    }

    @Test
    fun latestMatchingRecordWinsOverOlderReasonsAndUnrelatedNewerRecords() {
        val memory = record(ApplicationExitInfo.REASON_LOW_MEMORY)
        val userStop = record(ApplicationExitInfo.REASON_USER_REQUESTED, 300L)
        assertNull(asrExitNotice(session, listOf(memory, userStop), 1_000L))
        assertEquals(AsrExitKind.MEMORY, asrExitNotice(session,
            listOf(memory, userStop.copy(sessionToken = "unrelated")), 1_000L)!!.kind)
    }
}

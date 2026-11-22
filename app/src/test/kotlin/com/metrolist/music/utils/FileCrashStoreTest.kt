/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCrashStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `saves redacted report manages pending marker and caps stored files`() {
        val dir = tempFolder.newFolder("crashes")
        val store = FileCrashStore(dir)

        val report = CrashReport(
            id = "1000-0",
            timestamp = 1000L,
            appVersion = "1.2.2",
            deviceInfo = "Android 15",
            stackTrace = "RuntimeException at https://feed.example.com/path?token=topsecret",
            message = "Cookie: SAPISID=leak",
        )
        store.save(report)

        assertEquals("1000-0", store.pendingReportId())
        val loaded = store.get("1000-0")
        assertNotNull(loaded)
        assertFalse(loaded!!.stackTrace.contains("topsecret"))
        assertFalse(loaded.message!!.contains("leak"))

        store.acknowledge("1000-0")
        assertNull(store.pendingReportId())

        // Write 55 more reports -> cap at 50
        for (i in 1..55) {
            store.save(
                CrashReport(
                    id = "${2000 + i}-$i",
                    timestamp = (2000 + i).toLong(),
                    appVersion = "1.2.2",
                    deviceInfo = "Android 15",
                    stackTrace = "trace $i",
                ),
            )
        }
        assertEquals(50, store.list().size)
        assertEquals("2055-55", store.pendingReportId())

        store.clear()
        assertTrue(store.list().isEmpty())
        assertNull(store.pendingReportId())
    }

    @Test
    fun `oversized stack trace is truncated cleanly to valid json under 256 KiB`() {
        val dir = tempFolder.newFolder("crashes_large")
        val store = FileCrashStore(dir)
        val hugeTrace = "崩溃堆栈追踪示例\n".repeat(15_000)

        val id = "9999-1"
        store.save(
            CrashReport(
                id = id,
                timestamp = 9999L,
                appVersion = "1.2.2",
                deviceInfo = "Android 15",
                stackTrace = hugeTrace,
            ),
        )

        val file = File(dir, "$id.json")
        assertTrue(file.length() <= 256 * 1024)
        val parsed = store.get(id)
        assertNotNull(parsed)
        assertTrue(parsed!!.stackTrace.contains("...[truncated]"))
    }
}

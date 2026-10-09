/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.playback

import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCursorSnapshotTest {
    @Test
    fun `an empty download index is closed after reading`() {
        val cursor = TestCursor(count = 0)

        assertTrue(readDownloadSnapshot(cursor).isEmpty())
        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `an index movement failure still closes the cursor`() {
        val failure = IllegalStateException("Cannot advance index")
        val cursor = TestCursor(count = 1, movementFailure = failure)

        assertSame(failure, runCatching { readDownloadSnapshot(cursor) }.exceptionOrNull())
        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `an unreadable download row still closes the cursor`() {
        val failure = IllegalStateException("Cannot read download")
        val cursor = TestCursor(count = 1, rowFailure = failure)

        assertSame(failure, runCatching { readDownloadSnapshot(cursor) }.exceptionOrNull())
        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `closing failure does not hide the original index failure`() {
        val readFailure = IllegalStateException("Cannot advance index")
        val closeFailure = IllegalStateException("Cannot close index")
        val cursor = TestCursor(count = 1, movementFailure = readFailure, closeFailure = closeFailure)

        val failure = runCatching { readDownloadSnapshot(cursor) }.exceptionOrNull()

        assertSame(readFailure, failure)
        assertEquals(listOf(closeFailure), failure!!.suppressed.toList())
        assertEquals(1, cursor.closeCount)
    }

    private class TestCursor(
        private val count: Int,
        private val movementFailure: RuntimeException? = null,
        private val rowFailure: RuntimeException = IllegalStateException("Unexpected row access"),
        private val closeFailure: RuntimeException? = null,
    ) : DownloadCursor {
        private var position = -1
        var closeCount = 0
            private set

        override fun getDownload(): Download = throw rowFailure

        override fun getCount(): Int = count

        override fun getPosition(): Int = position

        override fun moveToPosition(position: Int): Boolean {
            movementFailure?.let { throw it }
            this.position = position
            return position in 0 until count
        }

        override fun isClosed(): Boolean = closeCount > 0

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }
}

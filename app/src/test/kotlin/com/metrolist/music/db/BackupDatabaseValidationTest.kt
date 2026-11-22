/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupDatabaseValidationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `accepts valid sqlite database with intact foreign keys`() {
        val dbFile = tempFolder.newFile("valid.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE parent(id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE child(id TEXT PRIMARY KEY NOT NULL, parentId TEXT NOT NULL REFERENCES parent(id))")
            db.execSQL("INSERT INTO parent VALUES ('p1')")
            db.execSQL("INSERT INTO child VALUES ('c1', 'p1')")
        }

        validateBackupDatabaseFile(dbFile.absolutePath)
    }

    @Test
    fun `rejects database containing orphaned foreign key rows`() {
        val dbFile = tempFolder.newFile("broken_fk.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE parent(id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE child(id TEXT PRIMARY KEY NOT NULL, parentId TEXT NOT NULL REFERENCES parent(id))")
            db.execSQL("INSERT INTO child VALUES ('c1', 'missing-parent')")
        }

        var failed = false
        try {
            validateBackupDatabaseFile(dbFile.absolutePath)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `rejects corrupted database file`() {
        val dbFile = tempFolder.newFile("corrupt.db")
        dbFile.writeText("this is definitely not sqlite3 format")

        var failed = false
        try {
            validateBackupDatabaseFile(dbFile.absolutePath)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}

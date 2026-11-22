/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

internal fun validateBackupDatabaseFile(dbPath: String) {
    SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                "Backup database failed integrity check"
            }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            check(!cursor.moveToFirst()) {
                "Backup database contains invalid foreign key relationships"
            }
        }
    }
}

internal fun createDatabaseSnapshot(
    context: Context,
    database: SupportSQLiteDatabase,
    source: File,
    destination: File,
) {
    check(destination.parentFile?.let { it.mkdirs() || it.isDirectory } != false) {
        "Could not create database snapshot directory"
    }
    destination.delete()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        database.execSQL("VACUUM main INTO '${destination.canonicalPath.replace("'", "''")}'")
        return
    }

    // API 26-29 fallback: checkpoint WAL into a private copy so the backup zip has zero stale WAL dependency.
    database.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    val workDir = File(context.cacheDir, "db_snapshot_tmp").apply { mkdirs() }
    val workDb = File(workDir, source.name)
    val workWal = File("${workDb.path}-wal")
    val workShm = File("${workDb.path}-shm")
    workDb.delete()
    workWal.delete()
    workShm.delete()
    try {
        source.copyTo(workDb, overwrite = true)
        File("${source.path}-wal").takeIf { it.isFile }?.copyTo(workWal, overwrite = true)
        File("${source.path}-shm").takeIf { it.isFile }?.copyTo(workShm, overwrite = true)
        if (workWal.isFile) {
            SQLiteDatabase.openDatabase(workDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { copyDb ->
                copyDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close()
            }
        }
        workWal.delete()
        workShm.delete()
        workDb.copyTo(destination, overwrite = true)
    } finally {
        workDb.delete()
        workWal.delete()
        workShm.delete()
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal fun removeRetiredAsrModelFiles(filesDirectory: File) {
    val root = filesDirectory.canonicalFile.toPath().resolve("podcast/asr")
    if (root.toFile().canonicalFile.toPath() != root) return
    val retired = root.resolve("zipformer-en")
    if (!Files.exists(retired, LinkOption.NOFOLLOW_LINKS)) return
    // walkFileTree does not follow links, including links within the retired directory.
    // A link to the active model or a user import is removed without touching its target.
    Files.walkFileTree(retired, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
            if (error != null) throw error
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal fun subtitleCacheFingerprint(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        repeat(Int.SIZE_BYTES) { byte -> digest.update((bytes.size ushr (byte * 8)).toByte()) }
        digest.update(bytes)
    }
    val alphabet = "0123456789abcdef"
    return buildString(64) {
        digest.digest().forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}

internal fun subtitleCueFingerprint(cue: SubtitleCue): String =
    subtitleCacheFingerprint("subtitle-cue-v1", cue.startMs.toString(), cue.endMs.toString(), cue.text)

/** Credentials restart failed requests without invalidating already paid-for translations. */
internal fun SubtitleTranslationConfig.cacheFingerprint(): String = subtitleCacheFingerprint(
    "subtitle-translation-v2",
    provider,
    targetLanguage,
    if (provider == "DeepL") deeplFormality else "",
    if (provider == "DeepL") "" else model,
    if (provider == "DeepL") "" else mode,
    if (provider == "DeepL") "" else systemPrompt.ifBlank { com.metrolist.music.constants.DEFAULT_PODCAST_AI_SYSTEM_PROMPT },
    if (provider == "DeepL" || provider == "Mistral") "" else
        runCatching { com.metrolist.music.api.completeTranslationUrl(baseUrl.ifBlank { "https://openrouter.ai/api/v1/chat/completions" }) }.getOrDefault(baseUrl),
)

internal fun SubtitleTranslationConfig.requestFingerprint(): String = subtitleCacheFingerprint(
    cacheFingerprint(),
    if (provider == "DeepL") deeplApiKey.trim() else apiKey.trim(),
)

/** A failed write must leave the last complete JSON readable. Both paths share one directory. */
internal fun writeSubtitleCacheAtomically(file: File, content: String) {
    val parent = checkNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "Could not create subtitle cache directory" }
    val pending = File(parent, ".${file.name}.pending")
    try {
        FileOutputStream(pending).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(
            pending.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        if (pending.exists()) pending.delete()
    }
}

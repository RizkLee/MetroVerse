package com.metrolist.innertube.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.PlaylistPage
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(requireComplete: Boolean = false): Result<PlaylistPage> =
    completedResult(requireComplete) {
        val page = getOrThrow()
        val songs = collectContinuationItems(page.songs, page.songsContinuation, requireComplete) { continuation ->
            YouTube.playlistContinuation(continuation, preserveEmptyContinuation = requireComplete).map {
                ContinuationItems(it.songs, it.continuation)
            }
        }
        PlaylistPage(
            playlist = page.playlist,
            songs = songs,
            songsContinuation = null,
            continuation = page.continuation,
        )
    }

@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(requireComplete: Boolean = false): Result<LibraryPage> =
    completedResult(requireComplete) {
        val page = getOrThrow()
        val items = collectContinuationItems(page.items, page.continuation, requireComplete) { continuation ->
            YouTube.libraryContinuation(continuation).map {
                ContinuationItems(it.items, it.continuation)
            }
        }
        LibraryPage(items = items, continuation = null)
    }

internal data class ContinuationItems<T>(val items: List<T>, val continuation: String?)

internal class IncompleteCollectionException(message: String) : IllegalStateException(message)

private suspend inline fun <T> completedResult(requireComplete: Boolean, block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(
            if (requireComplete && error !is IncompleteCollectionException) {
                // Sync failures may be logged; do not expose request URLs or continuation tokens.
                IncompleteCollectionException("Unable to read the complete collection")
            } else {
                error
            },
        )
    }

internal suspend fun <T> collectContinuationItems(
    initialItems: List<T>,
    initialContinuation: String?,
    requireComplete: Boolean,
    fetch: suspend (String) -> Result<ContinuationItems<T>>,
): List<T> {
    val items = initialItems.toMutableList()
    var continuation = initialContinuation
    val seenContinuations = mutableSetOf<String>()
    var requestCount = 0
    var consecutiveEmptyResponses = 0

    while (continuation != null) {
        if (requestCount >= 50) {
            if (requireComplete) throw IncompleteCollectionException("Collection pagination limit reached")
            break
        }
        if (!seenContinuations.add(continuation)) {
            if (requireComplete) throw IncompleteCollectionException("Collection pagination did not advance")
            break
        }
        requestCount++

        val page = try {
            fetch(continuation).getOrThrow()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (requireComplete) throw IncompleteCollectionException("Collection continuation request failed")
            break
        }
        items += page.items
        continuation = page.continuation
        consecutiveEmptyResponses = if (page.items.isEmpty()) consecutiveEmptyResponses + 1 else 0
        if (consecutiveEmptyResponses >= 2 && continuation != null) {
            if (requireComplete) throw IncompleteCollectionException("Collection returned consecutive empty pages")
            break
        }
    }
    return items
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split("; ")
        .filter { it.isNotEmpty() }
        .mapNotNull { part ->
            val splitIndex = part.indexOf('=')
            if (splitIndex == -1) null
            else part.substring(0, splitIndex) to part.substring(splitIndex + 1)
        }
        .toMap()

fun String.parseTime(): Int? {
    try {
        // YouTube Music returns duration with locale-dependent separators
        // (":" en-US, "." some locales, "," EU). Accept all.
        val parts = split(Regex("[:.,]")).map { it.toInt() }
        if (parts.size == 2) {
            return parts[0] * 60 + parts[1]
        }
        if (parts.size == 3) {
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}

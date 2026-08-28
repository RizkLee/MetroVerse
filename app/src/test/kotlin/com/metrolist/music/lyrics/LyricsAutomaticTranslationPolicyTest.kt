package com.metrolist.music.lyrics

import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.viewmodels.processedLyricsBelongToCurrentMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAutomaticTranslationPolicyTest {
    @Test
    fun `automatic translation requires a valid visible idle request`() {
        assertTrue(shouldRequest())
        assertFalse(shouldRequest(alwaysEnabled = false))
        assertFalse(shouldRequest(showLyrics = false))
        assertFalse(shouldRequest(hasLyrics = false))
        assertFalse(shouldRequest(hasApiKey = false))
        assertFalse(shouldRequest(hasVerifiedSongId = false))
        assertFalse(shouldRequest(hasMatchingStoredTranslation = true))
        assertFalse(shouldRequest(translationInProgress = true))
        assertFalse(shouldRequest(automaticTranslationSuppressed = true))
    }

    @Test
    fun `stale translation requests cannot update lyrics`() {
        assertTrue(
            canApplyLyricsTranslationResult(
                requestGeneration = 4,
                currentGeneration = 4,
                compositionActive = true,
                songId = "song-2",
                currentSongId = "song-2",
                automaticRequest = true,
                automaticTranslationSuppressed = false,
            ),
        )
        assertFalse(
            canApplyLyricsTranslationResult(
                requestGeneration = 3,
                currentGeneration = 4,
                compositionActive = true,
                songId = "song-2",
                currentSongId = "song-2",
                automaticRequest = true,
                automaticTranslationSuppressed = false,
            ),
        )
        assertFalse(
            canApplyLyricsTranslationResult(
                requestGeneration = 4,
                currentGeneration = 4,
                compositionActive = true,
                songId = "song-1",
                currentSongId = "song-2",
                automaticRequest = true,
                automaticTranslationSuppressed = false,
            ),
        )
        assertFalse(
            canApplyLyricsTranslationResult(
                requestGeneration = 4,
                currentGeneration = 4,
                compositionActive = true,
                songId = "song-2",
                currentSongId = "song-2",
                automaticRequest = true,
                automaticTranslationSuppressed = true,
            ),
        )
    }

    @Test
    fun `media and lyrics IDs must match before automatic translation`() {
        assertEquals("song", verifiedLyricsSongId(mediaSongId = "song", lyricsEntityId = "song"))
        assertNull(verifiedLyricsSongId(mediaSongId = "next-song", lyricsEntityId = "previous-song"))
        assertNull(verifiedLyricsSongId(mediaSongId = "song", lyricsEntityId = null))
        assertNull(verifiedLyricsSongId(mediaSongId = null, lyricsEntityId = "song"))
    }

    @Test
    fun `processed lines must belong to the current media and lyrics source`() {
        assertTrue(
            processedLyricsBelongToCurrentMedia(
                processedSourceLyrics = "current lyrics",
                currentLyrics = "current lyrics",
                lyricsEntityId = "song-2",
                mediaId = "song-2",
            ),
        )
        assertFalse(
            processedLyricsBelongToCurrentMedia(
                processedSourceLyrics = "previous lyrics",
                currentLyrics = "current lyrics",
                lyricsEntityId = "song-2",
                mediaId = "song-2",
            ),
        )
        assertFalse(
            processedLyricsBelongToCurrentMedia(
                processedSourceLyrics = "previous lyrics",
                currentLyrics = "previous lyrics",
                lyricsEntityId = "song-1",
                mediaId = "song-2",
            ),
        )
    }

    @Test
    fun `manual suppression applies only until the song changes`() {
        LyricsTranslationHelper.updateCurrentLyricsSong("song-1")
        LyricsTranslationHelper.suppressAutomaticTranslation("song-1")
        assertTrue(LyricsTranslationHelper.isAutomaticTranslationSuppressed("song-1"))

        LyricsTranslationHelper.updateCurrentLyricsSong("song-2")
        assertFalse(LyricsTranslationHelper.isAutomaticTranslationSuppressed("song-1"))
        assertFalse(LyricsTranslationHelper.isAutomaticTranslationSuppressed("song-2"))
    }

    @Test
    fun `matching persisted translation prevents another request`() {
        val sourceLyrics = "one\ntwo"
        val lyricsEntity =
            LyricsEntity(
                id = "song",
                lyrics = sourceLyrics,
                translatedLyrics = "uno\ndos",
                translationLanguage = "es",
                translationMode = storedLyricsTranslationMode("Literal", sourceLyrics),
            )

        assertTrue(
            hasMatchingStoredLyricsTranslation(
                lyricsEntity = lyricsEntity,
                targetLanguage = "es",
                mode = "Literal",
                requiredLineCount = 2,
            ),
        )
        assertFalse(
            hasMatchingStoredLyricsTranslation(
                lyricsEntity = lyricsEntity.copy(translationMode = "Literal"),
                targetLanguage = "es",
                mode = "Literal",
                requiredLineCount = 2,
            ),
        )
        assertFalse(
            hasMatchingStoredLyricsTranslation(
                lyricsEntity = lyricsEntity,
                targetLanguage = "fr",
                mode = "Literal",
                requiredLineCount = 2,
            ),
        )
        assertFalse(
            hasMatchingStoredLyricsTranslation(
                lyricsEntity = lyricsEntity,
                targetLanguage = "es",
                mode = "Literal",
                requiredLineCount = 3,
            ),
        )
    }

    private fun shouldRequest(
        alwaysEnabled: Boolean = true,
        showLyrics: Boolean = true,
        hasLyrics: Boolean = true,
        hasApiKey: Boolean = true,
        hasVerifiedSongId: Boolean = true,
        hasMatchingStoredTranslation: Boolean = false,
        translationInProgress: Boolean = false,
        automaticTranslationSuppressed: Boolean = false,
    ): Boolean =
        shouldRequestAutomaticLyricsTranslation(
            alwaysUseAiTranslation = alwaysEnabled,
            showLyrics = showLyrics,
            hasLyrics = hasLyrics,
            hasApiKey = hasApiKey,
            hasVerifiedSongId = hasVerifiedSongId,
            hasMatchingStoredTranslation = hasMatchingStoredTranslation,
            translationInProgress = translationInProgress,
            automaticTranslationSuppressed = automaticTranslationSuppressed,
        )
}

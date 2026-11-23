package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.ThumbnailRenderer
import com.metrolist.innertube.models.Thumbnails
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSummaryPageTest {
    @Test
    fun `card song inherits artist when response only contains type and duration`() {
        val renderer = songRenderer(
            title = "Howling",
            metadata = listOf(
                Run("Song", null),
                Run(" • ", null),
                Run("3:04", null),
                Run("6m\u00a0plays", null),
            ),
        )
        val fallbackArtist = Artist("Lupus Nocte", "UC123")

        val song = SearchSummaryPage.fromMusicResponsiveListItemRenderer(
            renderer,
            fallbackArtists = listOf(fallbackArtist),
        ) as SongItem

        assertEquals(listOf(fallbackArtist), song.artists)
        assertEquals(184, song.duration)
    }

    @Test
    fun `search result items preserve multi-run titles`() {
        val multiRunRenderer = songRenderer(
            titleRuns = listOf(
                Run("●", null),
                Run(" The Weeknd - Blinding Lights", null),
            ),
            metadata = listOf(
                Run("Aura Serenity", null),
                Run(" • ", null),
                Run("3:20", null),
            ),
        )

        val searchSong = SearchPage.toYTItem(multiRunRenderer) as SongItem
        val summarySong = SearchSummaryPage.fromMusicResponsiveListItemRenderer(multiRunRenderer) as SongItem

        assertEquals("● The Weeknd - Blinding Lights", searchSong.title)
        assertEquals("● The Weeknd - Blinding Lights", summarySong.title)
    }

    private fun songRenderer(
        title: String,
        metadata: List<Run>,
    ) = songRenderer(listOf(Run(title, null)), metadata)

    private fun songRenderer(
        titleRuns: List<Run>,
        metadata: List<Run>,
    ) = MusicResponsiveListItemRenderer(
        badges = null,
        fixedColumns = null,
        flexColumns = listOf(
            flexColumn(titleRuns),
            flexColumn(metadata),
        ),
        thumbnail = ThumbnailRenderer(
            musicThumbnailRenderer = ThumbnailRenderer.MusicThumbnailRenderer(
                thumbnail = Thumbnails(listOf(Thumbnail("https://example.com/cover.jpg", null, null))),
                thumbnailCrop = null,
                thumbnailScale = null,
            ),
            musicAnimatedThumbnailRenderer = null,
            croppedSquareThumbnailRenderer = null,
        ),
        menu = null,
        playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(
            playlistSetVideoId = null,
            videoId = "videoId",
        ),
        overlay = null,
        navigationEndpoint = null,
    )

    private fun flexColumn(runs: List<Run>) = MusicResponsiveListItemRenderer.FlexColumn(
        MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(Runs(runs)),
    )
}

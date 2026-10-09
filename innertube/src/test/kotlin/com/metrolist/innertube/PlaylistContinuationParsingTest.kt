package com.metrolist.innertube

import com.metrolist.innertube.models.Continuation
import com.metrolist.innertube.models.ContinuationItemRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.ResponseContext
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.ThumbnailRenderer
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.response.BrowseResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistContinuationParsingTest {
    @Test
    fun `strict parsing retains an empty page cursor in a later append action`() {
        val response = response(
            actions = listOf(
                BrowseResponse.ResponseAction(appendContinuationItemsAction = null),
                action(continuationItem("next")),
            ),
        )

        val strict = parsePlaylistContinuation(response, preserveEmptyContinuation = true)
        assertTrue(strict.songs.isEmpty())
        assertEquals("next", strict.continuation)
        assertNull(parsePlaylistContinuation(response).continuation)
    }

    @Test
    fun `strict parsing reads songs and continuation from every append action`() {
        val response = response(
            actions = listOf(
                action(songItem("first")),
                action(songItem("second"), continuationItem("next")),
            ),
        )

        val strict = parsePlaylistContinuation(response, preserveEmptyContinuation = true)
        assertEquals(listOf("first", "second"), strict.songs.map { it.id })
        assertEquals("next", strict.continuation)
        val default = parsePlaylistContinuation(response)
        assertEquals(listOf("first"), default.songs.map { it.id })
        assertNull(default.continuation)
    }

    @Test
    fun `strict parsing keeps shelf continuation without parseable songs`() {
        val response = response().copy(
            continuationContents = BrowseResponse.ContinuationContents(
                sectionListContinuation = null,
                musicPlaylistShelfContinuation = BrowseResponse.ContinuationContents.MusicPlaylistShelfContinuation(
                    contents = emptyList(),
                    continuations = listOf(Continuation(Continuation.NextContinuationData("next"))),
                ),
                gridContinuation = null,
                musicShelfContinuation = null,
            ),
        )

        assertEquals("next", parsePlaylistContinuation(response, preserveEmptyContinuation = true).continuation)
        assertNull(parsePlaylistContinuation(response).continuation)
    }

    @Test
    fun `terminal empty continuation remains terminal in strict mode`() {
        val parsed = parsePlaylistContinuation(response(), preserveEmptyContinuation = true)
        assertTrue(parsed.songs.isEmpty())
        assertNull(parsed.continuation)
    }

    private fun response(actions: List<BrowseResponse.ResponseAction> = emptyList()) = BrowseResponse(
        contents = null,
        continuationContents = null,
        onResponseReceivedActions = actions,
        header = null,
        microformat = null,
        responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
        background = null,
    )

    private fun action(vararg contents: MusicShelfRenderer.Content) = BrowseResponse.ResponseAction(
        BrowseResponse.ResponseAction.ContinuationItems(contents.toList()),
    )

    private fun continuationItem(token: String) = MusicShelfRenderer.Content(
        musicResponsiveListItemRenderer = null,
        musicMultiRowListItemRenderer = null,
        continuationItemRenderer = ContinuationItemRenderer(
            ContinuationItemRenderer.ContinuationEndpoint(
                ContinuationItemRenderer.ContinuationEndpoint.ContinuationCommand(token),
            ),
        ),
    )

    private fun songItem(id: String) = MusicShelfRenderer.Content(
        musicResponsiveListItemRenderer = MusicResponsiveListItemRenderer(
            badges = null,
            fixedColumns = null,
            flexColumns = listOf(id, "Artist").map {
                MusicResponsiveListItemRenderer.FlexColumn(
                    MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                        Runs(listOf(Run(it, null))),
                    ),
                )
            },
            thumbnail = ThumbnailRenderer(
                musicThumbnailRenderer = ThumbnailRenderer.MusicThumbnailRenderer(
                    thumbnail = Thumbnails(listOf(Thumbnail("https://example.invalid/art.jpg", 1, 1))),
                    thumbnailCrop = null,
                    thumbnailScale = null,
                ),
                musicAnimatedThumbnailRenderer = null,
                croppedSquareThumbnailRenderer = null,
            ),
            menu = null,
            playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData("set-$id", id),
            overlay = null,
            navigationEndpoint = null,
        ),
        musicMultiRowListItemRenderer = null,
        continuationItemRenderer = null,
    )
}

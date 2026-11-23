package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHelperTest {
    @Test
    fun `duration is not parsed as an artist`() {
        val artists = PageHelper.extractArtists(listOf(Run("3:42", null)))

        assertTrue(artists.isEmpty())
    }

    @Test
    fun `search metadata is not parsed as an artist`() {
        val runs = listOf(
            Run("Song", null),
            Run(" • ", null),
            Run("3:04", null),
            Run("6m\u00a0plays", null),
        )

        assertTrue(PageHelper.extractArtists(runs).isEmpty())
        assertEquals(184, PageHelper.extractDuration(runs))
    }

    @Test
    fun `linked artist is found after search type label`() {
        val runs = listOf(
            Run("Song", null),
            Run(" • ", null),
            Run(
                "Lupus Nocte",
                NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123")),
            ),
        )

        assertEquals(listOf(Artist("Lupus Nocte", "UC123")), PageHelper.extractArtists(runs))
    }

    @Test
    fun `unlinked artist name is retained`() {
        val artists = PageHelper.extractArtists(
            listOf(
                Run("Artist", null),
                Run(" • ", null),
                Run("Album", null),
                Run(" • ", null),
                Run("3:42", null),
            ),
        )

        assertEquals(listOf(Artist("Artist", null)), artists)
    }

    @Test
    fun `linked and unlinked artists are both retained`() {
        val artists = PageHelper.extractArtists(
            listOf(
                Run(
                    "Primary Artist",
                    NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123")),
                ),
                Run(" & ", null),
                Run("Featured Artist", null),
            ),
        )

        assertEquals(
            listOf(Artist("Primary Artist", "UC123"), Artist("Featured Artist", null)),
            artists,
        )
    }

    @Test
    fun `typed artist endpoint does not require a channel id`() {
        val endpoint = BrowseEndpoint(
            browseId = "MPLA123",
            browseEndpointContextSupportedConfigs = BrowseEndpointContextSupportedConfigs(
                BrowseEndpointContextMusicConfig(MUSIC_PAGE_TYPE_ARTIST),
            ),
        )

        assertEquals(
            listOf(Artist("Artist", "MPLA123")),
            PageHelper.extractArtists(
                listOf(Run("Artist", NavigationEndpoint(browseEndpoint = endpoint))),
            ),
        )
    }

    @Test
    fun `comma runs between linked artists do not emit separator artists`() {
        val runs = listOf(
            Run("The Weeknd", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC0WP5P-ufpRfjbN6OWwLB4A"))),
            Run(", ", null),
            Run("JENNIE", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123456"))),
            Run(" & ", null),
            Run("Lily Rose Depp", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC789012"))),
        )

        assertEquals(
            listOf(
                Artist("The Weeknd", "UC0WP5P-ufpRfjbN6OWwLB4A"),
                Artist("JENNIE", "UC123456"),
                Artist("Lily Rose Depp", "UC789012"),
            ),
            PageHelper.extractArtists(runs),
        )
    }

    @Test
    fun `joinTitleText joins multi-run titles and strips zero-width whitespace`() {
        assertEquals(
            "● The Weeknd - Blinding Lights",
            PageHelper.joinTitleText(
                listOf(
                    Run("●", null),
                    Run(" The Weeknd - Blinding Lights", null),
                ),
            ),
        )
        assertEquals(
            null,
            PageHelper.joinTitleText(listOf(Run("\u200B \uFEFF", null))),
        )
    }

    @Test
    fun `unlinked fallback filters separators and zero-width noise`() {
        val runs = listOf(
            Run("The Weeknd", null),
            Run(" & ", null),
            Run(", ", null),
            Run("●", null),
            Run("\u200B", null),
            Run("JENNIE", null),
        )

        assertEquals(
            listOf(Artist("The Weeknd", null), Artist("JENNIE", null)),
            PageHelper.extractArtists(runs),
        )
    }
}

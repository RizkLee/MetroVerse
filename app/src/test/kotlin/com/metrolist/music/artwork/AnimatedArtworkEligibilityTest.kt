/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.artwork

import com.metrolist.innertube.models.MUSIC_VIDEO_TYPE_PODCAST_EPISODE
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.music.constants.AnimatedCoverResolution
import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedArtworkEligibilityTest {
    private fun sampleMetadata(
        id: String = "video123",
        title: String = "Midnight City",
        artistName: String = "M83",
        albumTitle: String? = "Hurry Up, We're Dreaming",
        musicVideoType: String? = MUSIC_VIDEO_TYPE_ATV,
        isEpisode: Boolean = false,
        uploadEntityId: String? = null,
        mediaUrl: String? = null,
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = if (artistName.isBlank()) emptyList() else listOf(MediaMetadata.Artist("UC123", artistName)),
        duration = 240,
        album = albumTitle?.let { MediaMetadata.Album("MPREb_123", it) },
        musicVideoType = musicVideoType,
        isEpisode = isEpisode,
        uploadEntityId = uploadEntityId,
        mediaUrl = mediaUrl,
    )

    @Test
    fun `isEligibleForAnimatedCover accepts regular YouTube music tracks and rejects podcasts locals and uploads`() {
        assertTrue(isEligibleForAnimatedCover(sampleMetadata()))
        assertFalse(isEligibleForAnimatedCover(null))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(isEpisode = true)))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(musicVideoType = MUSIC_VIDEO_TYPE_PODCAST_EPISODE)))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(id = "rss_episode_abc")))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(mediaUrl = "https://cdn.example.com/ep.mp3")))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(uploadEntityId = "upload_1")))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(id = "local_99")))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(title = "   ")))
        assertFalse(isEligibleForAnimatedCover(sampleMetadata(artistName = "")))
    }

    @Test
    fun `isEligibleForAppleMusicArtist excludes podcast channels and local or private artists`() {
        assertTrue(isEligibleForAppleMusicArtist("UCabc123", "Daft Punk", isPodcastChannel = false, isLocalArtist = false))
        assertFalse(isEligibleForAppleMusicArtist("UCabc123", "Some Show", isPodcastChannel = true, isLocalArtist = false))
        assertFalse(isEligibleForAppleMusicArtist("UCabc123", "Local", isPodcastChannel = false, isLocalArtist = true))
        assertFalse(isEligibleForAppleMusicArtist("LA12345678", "Local Gen", isPodcastChannel = false, isLocalArtist = false))
        assertFalse(
            isEligibleForAppleMusicArtist(
                "FEmusic_library_privately_owned_artist_1",
                "Uploaded Artist",
                isPodcastChannel = false,
                isLocalArtist = false,
            ),
        )
        assertFalse(isEligibleForAppleMusicArtist("", "Artist", isPodcastChannel = false, isLocalArtist = false))
        assertFalse(isEligibleForAppleMusicArtist("UCabc123", "   ", isPodcastChannel = false, isLocalArtist = false))
    }

    @Test
    fun `isAnimatedArtworkAllowedOnNetwork respects mobile data toggle across all combinations`() {
        assertTrue(isAnimatedArtworkAllowedOnNetwork(disableOnMobileData = false, isCellular = false))
        assertTrue(isAnimatedArtworkAllowedOnNetwork(disableOnMobileData = false, isCellular = true))
        assertTrue(isAnimatedArtworkAllowedOnNetwork(disableOnMobileData = true, isCellular = false))
        assertFalse(isAnimatedArtworkAllowedOnNetwork(disableOnMobileData = true, isCellular = true))
    }

    @Test
    fun `explicitness edition stripping and release matching handle Apple Music conventions`() {
        assertEquals(true, resolveArtworkExplicitness("Random Album (Explicit)", false))
        assertEquals(false, resolveArtworkExplicitness("Random Album - Clean", true))
        assertEquals(true, resolveArtworkExplicitness(null, true))
        assertNull(resolveArtworkExplicitness("Plain Album", true))
        assertEquals(null, resolveArtworkExplicitness("Plain Album (Single)", true))

        assertEquals("Random Album", stripArtworkEditionSuffix("Random Album (Explicit)"))
        assertEquals("Random Album - Single", stripArtworkEditionSuffix("Random Album (Explicit) - Single"))

        assertTrue(matchesAppleMusicExplicitness(null, "explicit", explicit = true, albumKnown = true))
        assertTrue(matchesAppleMusicExplicitness("cleaned", "explicit", explicit = false, albumKnown = false))
        assertFalse(matchesAppleMusicExplicitness("explicit", null, explicit = false, albumKnown = false))

        assertEquals("song name", cleanTitleText("Song Name (Official Music Video) [HD]"))
        assertTrue(albumNamesMatch("Discovery (Explicit)", "Discovery"))
        assertTrue(isSelfTitledRelease("Blue - Single", "blue"))
    }

    @Test
    fun `isTrustedArtworkUrl only accepts official Apple CDN origins over HTTPS without credentials`() {
        assertTrue(isTrustedArtworkUrl("https://itunes.apple.com/search?term=test"))
        assertTrue(isTrustedArtworkUrl("https://amp-api.music.apple.com/v1/catalog/us/albums/1"))
        assertTrue(isTrustedArtworkUrl("https://is1-ssl.mzstatic.com/image/thumb/x.webp"))
        assertTrue(isTrustedArtworkUrl("https://mvod.itunes.apple.com/itunes-assets/a.m3u8"))
        assertTrue(isTrustedArtworkUrl("https://aod-ssl.akamaized.net/video/master.m3u8"))

        assertFalse(isTrustedArtworkUrl("http://itunes.apple.com/search"))
        assertFalse(isTrustedArtworkUrl("https://user@itunes.apple.com/search"))
        assertFalse(isTrustedArtworkUrl("https://itunes.apple.com:8443/search"))
        assertFalse(isTrustedArtworkUrl("https://evil.apple.com.attacker.com/x.m3u8"))
        assertFalse(isTrustedArtworkUrl("https://example.com/x.m3u8"))
    }

    @Test
    fun `selectBestHlsVariant honors resolution cap breaks ties by bandwidth and rejects untrusted URLs`() {
        val master = "https://mvod.itunes.apple.com/path/master.m3u8"
        val playlist =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=480x480
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=720x720
            med_lowbw.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=720x720
            med_highbw.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1080x1080
            /abs/high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=2160x2160
            https://evil.example.com/untrusted.m3u8
            """.trimIndent()

        assertEquals(
            "https://mvod.itunes.apple.com/path/low.m3u8",
            selectBestHlsVariant(master, playlist, AnimatedCoverResolution.LOW_360),
        )
        assertEquals(
            "https://mvod.itunes.apple.com/path/med_highbw.m3u8",
            selectBestHlsVariant(master, playlist, AnimatedCoverResolution.MEDIUM_720),
        )
        assertEquals(
            "https://mvod.itunes.apple.com/abs/high.m3u8",
            selectBestHlsVariant(master, playlist, AnimatedCoverResolution.HIGH_1080),
        )
        assertEquals(
            "https://mvod.itunes.apple.com/abs/high.m3u8",
            selectBestHlsVariant(master, playlist, AnimatedCoverResolution.ORIGINAL),
        )
        assertNull(
            selectBestHlsVariant(
                master,
                "#EXT-X-STREAM-INF:BANDWIDTH=5000,RESOLUTION=720x720\nhttps://evil.example.com/v.m3u8",
                AnimatedCoverResolution.MEDIUM_720,
            ),
        )
    }

    @Test
    fun `formatAppleLocaleParam produces valid Apple storefront locale tags`() {
        assertEquals("zh-hans-us", formatAppleLocaleParam("zh-CN", "US"))
        assertEquals("zh-hant-tw", formatAppleLocaleParam("zh-TW", "TW"))
        assertEquals("en-us", formatAppleLocaleParam("en", "US"))
        assertEquals("en-gb", formatAppleLocaleParam("en-GB", "US"))
        assertNull(formatAppleLocaleParam("", "US"))
    }

    @Test
    fun `selectBestHlsVariant prefers SDR variants over HDR variants when available`() {
        val master = "https://mvod.itunes.apple.com/path/master.m3u8"
        val mixedPlaylist =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1080x1080,VIDEO-RANGE=PQ
            hdr_1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1080x1080,VIDEO-RANGE=SDR
            sdr_1080.m3u8
            """.trimIndent()
        assertEquals(
            "https://mvod.itunes.apple.com/path/sdr_1080.m3u8",
            selectBestHlsVariant(master, mixedPlaylist, AnimatedCoverResolution.HIGH_1080),
        )

        val hdrOnlyPlaylist =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=720x720,VIDEO-RANGE=PQ
            hdr_720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1080x1080,VIDEO-RANGE=HLG
            hdr_1080.m3u8
            """.trimIndent()
        assertEquals(
            "https://mvod.itunes.apple.com/path/hdr_1080.m3u8",
            selectBestHlsVariant(master, hdrOnlyPlaylist, AnimatedCoverResolution.HIGH_1080),
        )
    }
}

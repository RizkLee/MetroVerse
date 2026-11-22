/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.Application
import androidx.datastore.preferences.core.preferencesOf
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.innertube.models.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.innertube.models.MUSIC_VIDEO_TYPE_PODCAST_EPISODE
import com.metrolist.innertube.models.MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideShortSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.MinimumSongDurationSecondsKey
import com.metrolist.music.constants.ExplicitOnlyKey
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ContentFilterTest {
    @Test
    fun `explicit filtering supports mutual exclusion and spares non-explicit youtube podcasts`() {
        val cleanSong = songItem(id = "s1", explicit = false)
        val explicitSong = songItem(id = "s2", explicit = true)
        val cleanYtEpisode = EpisodeItem(
            id = "ep1",
            title = "Clean YT Episode",
            author = Artist("Host", "UC_HOST"),
            thumbnail = "https://example.com/e.jpg",
            explicit = false,
        ).asSongItem()

        val hideExplicitSnapshot = ContentFilterSnapshot(hideExplicit = true)
        assertFalse(hideExplicitSnapshot.shouldHide(cleanSong))
        assertTrue(hideExplicitSnapshot.shouldHide(explicitSong))

        val explicitOnlySnapshot = ContentFilterSnapshot(explicitOnly = true)
        assertTrue(explicitOnlySnapshot.shouldHide(cleanSong))
        assertFalse(explicitOnlySnapshot.shouldHide(explicitSong))
        assertFalse(explicitOnlySnapshot.shouldHide(cleanYtEpisode))

        val conflictingPrefs = preferencesOf(
            HideExplicitKey to true,
            ExplicitOnlyKey to true,
        )
        val normalized = ContentFilter.fromPreferences(conflictingPrefs, emptySet())
        assertTrue(normalized.hideExplicit)
        assertFalse(normalized.explicitOnly)
    }

    @Test
    fun `video and non-musical video filters respect official mvs uploads and local unspecified videos`() {
        val officialMv = songItem(id = "mv", musicVideoType = MUSIC_VIDEO_TYPE_OMV)
        val ugcVlog = songItem(id = "ugc", musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
        val liveClip = songItem(id = "live", musicVideoType = MUSIC_VIDEO_TYPE_LIVE)
        val uploadedSong = songItem(id = "up", musicVideoType = MUSIC_VIDEO_TYPE_OMV, uploadEntityId = "ENT1")

        val hideVideos = ContentFilterSnapshot(hideVideoSongs = true)
        assertTrue(hideVideos.shouldHide(officialMv))
        assertTrue(hideVideos.shouldHide(ugcVlog))
        assertFalse(hideVideos.shouldHide(uploadedSong))

        val hideNonMusical = ContentFilterSnapshot(hideNonMusicalVideos = true)
        assertFalse(hideNonMusical.shouldHide(officialMv))
        assertTrue(hideNonMusical.shouldHide(ugcVlog))
        assertTrue(hideNonMusical.shouldHide(liveClip))

        val localVideoSong = Song(
            song = SongEntity(id = "local_v", title = "Local Video Song", duration = 200, isVideo = true),
            artists = emptyList(),
        )
        assertEquals(MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO, localVideoSong.toMediaMetadata().musicVideoType)
        assertFalse(hideNonMusical.shouldHide(localVideoSong.toMediaMetadata()))
        assertTrue(hideVideos.shouldHide(localVideoSong.toMediaMetadata()))
    }

    @Test
    fun `short songs duration slider clamps bounds hides ss playlists and supports legacy shorts fallback`() {
        val prefs = preferencesOf(
            HideYoutubeShortsKey to true,
            MinimumSongDurationSecondsKey to 999,
        )
        val snapshot = ContentFilter.fromPreferences(prefs, emptySet())
        assertTrue(snapshot.hideShortSongs)
        assertEquals(180, snapshot.effectiveMinimumDurationSeconds)

        assertTrue(snapshot.shouldHide(songItem(id = "short", duration = 45)))
        assertFalse(snapshot.shouldHide(songItem(id = "unknown", duration = -1)))
        assertFalse(snapshot.shouldHide(songItem(id = "zero", duration = 0)))
        assertFalse(snapshot.shouldHide(songItem(id = "long", duration = 200)))
        assertTrue(
            snapshot.shouldHide(
                PlaylistItem(
                    id = "SS123",
                    title = "Shorts",
                    author = null,
                    songCountText = null,
                    thumbnail = null,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                ),
            ),
        )

        val explicitOffPrefs = preferencesOf(
            HideShortSongsKey to false,
            HideYoutubeShortsKey to true,
        )
        assertFalse(ContentFilter.fromPreferences(explicitOffPrefs, emptySet()).hideShortSongs)
    }

    @Test
    fun `ai slop blocklist matches artists by channel id or handle only when enabled`() {
        val slopSong = songItem(
            id = "slop1",
            artists = listOf(Artist("Fake AI Band", "UC1234567890123456789012")),
        )
        val entries = setOf("uc1234567890123456789012", "@slopcreator")

        assertFalse(ContentFilterSnapshot(blockAiSlopArtists = false, aiSlopEntries = entries).shouldHide(slopSong))
        assertTrue(ContentFilterSnapshot(blockAiSlopArtists = true, aiSlopEntries = entries).shouldHide(slopSong))

        val handleSong = songItem(
            id = "slop2",
            artists = listOf(Artist("SlopCreator", null)),
        )
        assertTrue(ContentFilterSnapshot(blockAiSlopArtists = true, aiSlopEntries = entries).shouldHide(handleSong))
    }

    @Test
    fun `hide podcasts hides youtube podcasts and chips while strictly exempting open rss podcasts`() {
        val strictSnapshot = ContentFilterSnapshot(
            hideExplicit = true,
            blockAiSlopArtists = true,
            hideVideoSongs = true,
            hideNonMusicalVideos = true,
            hideShortSongs = true,
            minimumSongDurationSeconds = 180,
            hidePodcasts = true,
            aiSlopEntries = setOf("@slophost", "uc9999999999999999999999"),
        )

        // YouTube podcast objects ARE hidden
        assertTrue(
            strictSnapshot.shouldHide(
                PodcastItem(
                    id = "MPSP1",
                    title = "YT Show",
                    author = Artist("Host", null),
                    episodeCountText = null,
                    thumbnail = null,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                ),
            ),
        )
        assertTrue(
            strictSnapshot.shouldHide(
                PodcastEntity(
                    id = "MPSP1",
                    title = "YT Show",
                    feedUrl = null,
                ),
            ),
        )
        assertTrue(
            strictSnapshot.shouldHide(
                Song(
                    song = SongEntity(id = "yt_ep", title = "YT Ep", isEpisode = true, duration = 600),
                    artists = emptyList(),
                ),
            ),
        )

        val homePage = HomePage(
            chips = listOf(
                HomePage.Chip("Podcasts", BrowseEndpoint("FEmusic_podcast"), null),
                HomePage.Chip("Energize", BrowseEndpoint("FEmusic_moods"), null),
            ),
            sections = listOf(
                HomePage.Section(
                    title = "Podcasts",
                    label = null,
                    thumbnail = null,
                    endpoint = BrowseEndpoint("FEmusic_podcast"),
                    items = listOf(songItem("s_ok", explicit = false, duration = 220)),
                ),
                HomePage.Section(
                    title = "Music",
                    label = null,
                    thumbnail = null,
                    endpoint = BrowseEndpoint("FEmusic_home"),
                    items = listOf(songItem("s_clean", explicit = false, duration = 220)),
                ),
            ),
        )
        val filteredHome = strictSnapshot.filterHomePage(homePage)
        assertEquals(listOf("Energize"), filteredHome.chips?.map { it.title })
        assertEquals(listOf("Music"), filteredHome.sections.map { it.title })

        // Open RSS entities are NEVER hidden even when all filters match!
        val rssPodcast = PodcastEntity(
            id = "rss_podcast_abcdef",
            title = "Open RSS Show",
            author = "@slophost",
            channelId = "UC9999999999999999999999",
            feedUrl = "https://example.com/feed.xml",
        )
        assertFalse(strictSnapshot.shouldHide(rssPodcast))

        val rssEpisode = Song(
            song = SongEntity(
                id = "rss_episode_1234",
                title = "Open RSS Episode",
                duration = 10,
                explicit = true,
                isVideo = true,
                isEpisode = true,
                mediaUrl = "https://cdn.example.com/ep.mp3",
            ),
            artists = listOf(ArtistEntity(id = "UC9999999999999999999999", name = "@slophost")),
        )
        assertFalse(strictSnapshot.shouldHide(rssEpisode))
        assertFalse(strictSnapshot.shouldHide(rssEpisode.toMediaMetadata()))

        val rssMetadataByUrlOnly = MediaMetadata(
            id = "custom-guid-1",
            title = "RSS by URL",
            artists = emptyList(),
            duration = 5,
            explicit = true,
            isEpisode = true,
            musicVideoType = MUSIC_VIDEO_TYPE_PODCAST_EPISODE,
            mediaUrl = "https://cdn.example.com/audio.m4a",
        )
        assertFalse(strictSnapshot.shouldHide(rssMetadataByUrlOnly))
    }

    private fun songItem(
        id: String,
        explicit: Boolean = false,
        duration: Int? = 200,
        musicVideoType: String? = null,
        artists: List<Artist> = listOf(Artist("Artist", "UC_LEGIT")),
        uploadEntityId: String? = null,
    ) = SongItem(
        id = id,
        title = "Title $id",
        artists = artists,
        album = Album("Album", "MPREb_1"),
        duration = duration,
        musicVideoType = musicVideoType,
        thumbnail = "https://example.com/$id.jpg",
        explicit = explicit,
        uploadEntityId = uploadEntityId,
    )
}

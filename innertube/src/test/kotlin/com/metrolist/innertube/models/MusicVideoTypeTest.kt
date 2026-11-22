/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVideoTypeTest {
    @Test
    fun `parses video and non-video musicVideoType variants accurately`() {
        assertEquals(MusicVideoType.OMV, parseMusicVideoType("MUSIC_VIDEO_TYPE_OMV"))
        assertEquals(MusicVideoType.OMV, parseMusicVideoType("OFFICIAL_SOURCE_MUSIC_VIDEO"))
        assertEquals(MusicVideoType.UGC, parseMusicVideoType("MUSIC_VIDEO_TYPE_UGC"))
        assertEquals(MusicVideoType.ATV, parseMusicVideoType("MUSIC_VIDEO_TYPE_ATV"))
        assertEquals(MusicVideoType.PODCAST_EPISODE, parseMusicVideoType("MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))
        assertEquals(MusicVideoType.LIVE, parseMusicVideoType("MUSIC_VIDEO_TYPE_LIVE"))
        assertEquals(MusicVideoType.VIDEO, parseMusicVideoType("MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO"))
        assertEquals(MusicVideoType.UNKNOWN, parseMusicVideoType(null))

        assertTrue(isVideoType("MUSIC_VIDEO_TYPE_OMV"))
        assertTrue(isVideoType("MUSIC_VIDEO_TYPE_UGC"))
        assertTrue(isVideoType("MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO"))
        assertTrue(isVideoType("MUSIC_VIDEO_TYPE_LIVE"))
        assertFalse(isVideoType("MUSIC_VIDEO_TYPE_ATV"))
        assertFalse(isVideoType("MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))
        assertFalse(isVideoType("MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"))
        assertTrue(isUploadedType("MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"))
    }

    @Test
    fun `song item video and non-musical classification handles uploads videos and podcasts`() {
        val officialMv = song(musicVideoType = MUSIC_VIDEO_TYPE_OMV)
        val ugcVideo = song(musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
        val unspecifiedVideo = song(musicVideoType = MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO)
        val uploadedWithVideoType = song(musicVideoType = MUSIC_VIDEO_TYPE_OMV, uploadEntityId = "PRIV_1")
        val ytPodcastEpisode = EpisodeItem(
            id = "ep1",
            title = "Episode",
            author = null,
            thumbnail = "https://example.com/t.jpg",
        ).asSongItem()

        assertTrue(officialMv.isVideoSong)
        assertTrue(officialMv.isOfficialMusicVideo)
        assertFalse(officialMv.isNonMusicalVideo)

        assertTrue(ugcVideo.isVideoSong)
        assertFalse(ugcVideo.isOfficialMusicVideo)
        assertTrue(ugcVideo.isNonMusicalVideo)

        assertTrue(unspecifiedVideo.isVideoSong)
        assertFalse(unspecifiedVideo.isNonMusicalVideo)

        assertFalse(uploadedWithVideoType.isVideoSong)
        assertFalse(ytPodcastEpisode.isVideoSong)
        assertTrue(ytPodcastEpisode.isYouTubePodcast)
    }

    @Test
    fun `podcast browse ids are detected without touching music browse ids`() {
        assertTrue("FEmusic_podcast_home".isPodcastBrowseId())
        assertTrue("FEmusic_episode".isPodcastBrowseId())
        assertTrue("MPSPPL12345".isPodcastBrowseId())
        assertTrue("RDPN".isPodcastBrowseId())
        assertTrue("SE".isPodcastBrowseId())
        assertFalse("FEmusic_home".isPodcastBrowseId())
        assertFalse("MPREb_123".isPodcastBrowseId())
        assertFalse((null as String?).isPodcastBrowseId())
    }

    private fun song(
        musicVideoType: String? = null,
        uploadEntityId: String? = null,
    ) = SongItem(
        id = "id-1",
        title = "Track",
        artists = emptyList(),
        thumbnail = "https://example.com/thumb.jpg",
        musicVideoType = musicVideoType,
        uploadEntityId = uploadEntityId,
    )
}

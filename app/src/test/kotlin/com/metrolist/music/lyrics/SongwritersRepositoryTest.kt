package com.metrolist.music.lyrics

import android.app.Application
import com.metrolist.music.betterlyrics.TTMLParser
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class SongwritersRepositoryTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ProviderSongwritersCache.clearForTest()
        app.cacheDir.resolve("songwriters_cache.json").delete()
    }

    @Test
    fun `parseSongwriters extracts distinct non-blank names from iTunesMetadata`() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <head>
                <metadata>
                  <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                    <songwriters>
                      <songwriter>Abel Tesfaye</songwriter>
                      <songwriter> Max Martin </songwriter>
                      <songwriter>Abel Tesfaye</songwriter>
                      <songwriter>   </songwriter>
                    </songwriters>
                  </iTunesMetadata>
                </metadata>
              </head>
              <body />
            </tt>
            """.trimIndent()

        assertEquals(listOf("Abel Tesfaye", "Max Martin"), TTMLParser.parseSongwriters(ttml))
    }

    @Test
    fun `repository prefers cached provider writers and falls back from MPTC to BetterLyrics`() =
        runBlocking {
            val metaA = MediaMetadata(id = "vidA", title = "Song A", artists = listOf(MediaMetadata.Artist(null, "Artist A")), duration = 200)
            ProviderSongwritersCache.put(app, "vidA", listOf("Cached Writer"))

            var mptcCalls = 0
            val repoWithCached =
                SongwritersRepository(
                    context = app,
                    localeTagProvider = { "zh-CN" },
                    fetchYouTubeSongwriters = {
                        mptcCalls++
                        Result.success(listOf("MPTC Writer"))
                    },
                    isBetterLyricsEnabled = { true },
                    fetchBetterLyricsSongwriters = { listOf("BL Writer") },
                )
            assertEquals(listOf("Cached Writer"), repoWithCached.getSongwriters(metaA))
            assertEquals(0, mptcCalls)

            val metaB = MediaMetadata(id = "vidB", title = "Song B", artists = listOf(MediaMetadata.Artist(null, "Artist B")), duration = 210)
            val repoFallback =
                SongwritersRepository(
                    context = app,
                    localeTagProvider = { "zh-CN" },
                    fetchYouTubeSongwriters = { Result.success(emptyList()) },
                    isBetterLyricsEnabled = { true },
                    fetchBetterLyricsSongwriters = { listOf("TTML Writer 1", "TTML Writer 2") },
                )
            assertEquals(listOf("TTML Writer 1", "TTML Writer 2"), repoFallback.getSongwriters(metaB))
            // Second read should hit persisted ProviderSongwritersCache immediately.
            assertEquals(listOf("TTML Writer 1", "TTML Writer 2"), repoFallback.getSongwriters("vidB"))
        }
}

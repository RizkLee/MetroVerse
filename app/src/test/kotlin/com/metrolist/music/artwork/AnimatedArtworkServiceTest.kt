/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.artwork

import android.app.Application
import com.metrolist.music.constants.AnimatedCoverResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class AnimatedArtworkServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        tempDir = Files.createTempDirectory("artwork_service_test").toFile()
        client = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.close()
        tempDir.deleteRecursively()
    }

    private fun testJwt(exp: Long): String {
        val header =
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"ES256","typ":"JWT"}""".toByteArray())
        val payload =
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"iss":"AMPWebPlay","exp":$exp}""".toByteArray())
        return "$header.$payload.signature"
    }

    private fun newService(nowSeconds: Long = 1_700_000_000L): AnimatedArtworkService {
        val base = server.url("").toString().trimEnd('/')
        return AnimatedArtworkService(
            cacheDir = tempDir,
            httpClientProvider = { client },
            localeProvider = { "us" to "en" },
            endpoints =
                AnimatedArtworkService.Endpoints(
                    itunesBaseUrl = base,
                    appleMusicWebBaseUrl = base,
                    ampApiBaseUrl = base,
                ),
            urlTrustCheck = { true },
            timeSecondsProvider = { nowSeconds },
            cacheLoadDispatcher = Dispatchers.IO,
        )
    }

    @Test
    fun `fetchAnimatedCover resolves best HLS variant caches in memory and disk and clears cleanly`() =
        runBlocking {
            val base = server.url("").toString().trimEnd('/')
            val now = 1_700_000_000L
            val jwt = testJwt(now + 3600L)
            val requestCount = AtomicInteger(0)

            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requestCount.incrementAndGet()
                        val path = request.url.encodedPath
                        return when {
                            path == "/search" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        {
                                          "resultCount": 1,
                                          "results": [
                                            {
                                              "kind": "song",
                                              "trackId": 1001,
                                              "trackName": "Midnight City",
                                              "artistName": "M83",
                                              "collectionId": 555,
                                              "collectionName": "Hurry Up, We're Dreaming"
                                            }
                                          ]
                                        }
                                        """.trimIndent(),
                                )
                            }

                            path == "/us/browse" -> {
                                MockResponse(
                                    code = 200,
                                    body = """<html><script src="/assets/index-1234.js"></script></html>""",
                                )
                            }

                            path == "/assets/index-1234.js" -> {
                                MockResponse(
                                    code = 200,
                                    body = """const tok="$jwt";""",
                                )
                            }

                            path == "/v1/catalog/us/albums/555" -> {
                                assertEquals("Bearer $jwt", request.headers["Authorization"])
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        {
                                          "resources": {
                                            "albums": {
                                              "555": {
                                                "attributes": {
                                                  "editorialVideo": {
                                                    "motionSquareVideo1x1": {
                                                      "video": "$base/hls/square/master.m3u8"
                                                    },
                                                    "motionTallVideo3x4": {
                                                      "video": "$base/hls/tall/master.m3u8"
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                )
                            }

                            path == "/hls/square/master.m3u8" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        #EXTM3U
                                        #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=720x720
                                        sq720.m3u8
                                        #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1080x1080
                                        sq1080.m3u8
                                        """.trimIndent(),
                                )
                            }

                            path == "/hls/tall/master.m3u8" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        #EXTM3U
                                        #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=720x960
                                        tall720.m3u8
                                        """.trimIndent(),
                                )
                            }

                            else -> MockResponse(code = 404)
                        }
                    }
                }

            val service = newService(now)
            val result =
                service.fetchAnimatedCover(
                    title = "Midnight City",
                    artist = "M83",
                    album = "Hurry Up, We're Dreaming",
                    resolution = AnimatedCoverResolution.MEDIUM_720,
                )
            assertNotNull(result)
            assertEquals("$base/hls/square/sq720.m3u8", result?.url)
            assertEquals("$base/hls/tall/tall720.m3u8", result?.tallUrl)

            val callsAfterFirstFetch = requestCount.get()
            assertTrue(callsAfterFirstFetch >= 5)

            // Subsequent fetch hits in-memory cache without network calls.
            val cached =
                service.fetchAnimatedCover(
                    title = "Midnight City",
                    artist = "M83",
                    album = "Hurry Up, We're Dreaming",
                    resolution = AnimatedCoverResolution.MEDIUM_720,
                )
            assertEquals(result, cached)
            assertEquals(callsAfterFirstFetch, requestCount.get())

            // Clearing cache forces fresh fetch.
            service.clearCache()
            val refetched =
                service.fetchAnimatedCover(
                    title = "Midnight City",
                    artist = "M83",
                    album = "Hurry Up, We're Dreaming",
                    resolution = AnimatedCoverResolution.MEDIUM_720,
                )
            assertEquals(result, refetched)
            assertTrue(requestCount.get() > callsAfterFirstFetch)
            service.dispose()
        }

    @Test
    fun `fetchArtistArtwork returns image and video and resolves duplicate exact-name artists`() =
        runBlocking {
            val base = server.url("").toString().trimEnd('/')
            val now = 1_700_000_000L
            val jwt = testJwt(now + 3600L)

            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.url.encodedPath
                        val term = request.url.queryParameter("term")
                        return when {
                            path == "/search" && term == "Daft Punk" -> {
                                MockResponse(
                                    code = 200,
                                    body = """{"resultCount":1,"results":[{"artistName":"Daft Punk","artistId":5468295}]}""",
                                )
                            }

                            path == "/search" && term == "Common Name" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        {
                                          "resultCount": 2,
                                          "results": [
                                            {"artistName": "Common Name", "artistId": 10},
                                            {"artistName": "Common Name", "artistId": 20}
                                          ]
                                        }
                                        """.trimIndent(),
                                )
                            }

                            path == "/us/browse" -> {
                                MockResponse(code = 200, body = """<script src="/assets/index-abc.js"></script>""")
                            }

                            path == "/assets/index-abc.js" -> {
                                MockResponse(code = 200, body = """"$jwt"""")
                            }

                            path == "/v1/catalog/us/artists/5468295" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        {
                                          "resources": {
                                            "artists": {
                                              "5468295": {
                                                "attributes": {
                                                  "artwork": {
                                                    "url": "$base/images/{w}x{h}{c}.{f}",
                                                    "width": 2400,
                                                    "height": 2400
                                                  },
                                                  "editorialVideo": {
                                                    "motionArtistWide16x9": {
                                                      "video": "$base/hls/artist/master_wide.m3u8"
                                                    },
                                                    "motionArtistSquare1x1": {
                                                      "video": "$base/hls/artist/master.m3u8"
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                )
                            }

                            path == "/v1/catalog/us/artists/10" -> {
                                MockResponse(code = 200, body = """{"resources":{"artists":{"10":{"attributes":{}}}}}""")
                            }

                            path == "/v1/catalog/us/artists/20" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        {
                                          "resources": {
                                            "artists": {
                                              "20": {
                                                "attributes": {
                                                  "artwork": {
                                                    "url": "$base/images/{w}x{h}{c}.{f}",
                                                    "width": 1200,
                                                    "height": 1200
                                                  },
                                                  "editorialVideo": {
                                                    "motionArtistWide16x9": {
                                                      "video": "$base/hls/artist/master_wide.m3u8"
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                )
                            }

                            path == "/hls/artist/master.m3u8" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        #EXTM3U
                                        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
                                        artist1080.m3u8
                                        """.trimIndent(),
                                )
                            }

                            path == "/hls/artist/master_wide.m3u8" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        #EXTM3U
                                        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
                                        artistWide1080.m3u8
                                        """.trimIndent(),
                                )
                            }

                            else -> MockResponse(code = 404)
                        }
                    }
                }

            val service = newService(now)
            val found =
                service.fetchArtistArtwork(
                    artist = "Daft Punk",
                    includeImage = true,
                    includeVideo = true,
                    videoResolution = AnimatedCoverResolution.HIGH_1080,
                )
            assertTrue(found is ArtistArtworkFetchResult.Found)
            val artwork = (found as ArtistArtworkFetchResult.Found).artwork
            assertEquals("$base/images/2400x2400bb.webp", artwork.imageUrl)
            assertEquals("$base/hls/artist/artist1080.m3u8", artwork.videoUrl)

            val duplicateResolved =
                service.fetchArtistArtwork(
                    artist = "Common Name",
                    includeImage = true,
                    includeVideo = false,
                    videoResolution = AnimatedCoverResolution.MEDIUM_720,
                )
            assertTrue(duplicateResolved is ArtistArtworkFetchResult.Found)
            assertEquals("$base/images/1200x1200bb.webp", (duplicateResolved as ArtistArtworkFetchResult.Found).artwork.imageUrl)

            val wideFallback =
                service.fetchArtistArtwork(
                    artist = "Common Name",
                    includeImage = true,
                    includeVideo = true,
                    videoResolution = AnimatedCoverResolution.HIGH_1080,
                )
            assertTrue(wideFallback is ArtistArtworkFetchResult.Found)
            assertEquals("$base/hls/artist/artistWide1080.m3u8", (wideFallback as ArtistArtworkFetchResult.Found).artwork.videoUrl)

            val disabled =
                service.fetchArtistArtwork(
                    artist = "Daft Punk",
                    includeImage = false,
                    includeVideo = false,
                    videoResolution = AnimatedCoverResolution.MEDIUM_720,
                )
            assertEquals(ArtistArtworkFetchResult.Unavailable, disabled)
            assertNull(service.fetchAnimatedCover("", "M83"))
            service.dispose()
        }

    @Test
    fun `fetchAnimatedCover resolves track when album is missing and does not cache network errors`() =
        runBlocking {
            val base = server.url("").toString().trimEnd('/')
            val now = 1_700_000_000L
            val jwt = testJwt(now + 3600L)
            val failSearch = java.util.concurrent.atomic.AtomicBoolean(true)

            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.url.encodedPath
                        return when {
                            path == "/search" && failSearch.get() -> MockResponse(code = 500)
                            path == "/search" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        {
                                          "resultCount": 1,
                                          "results": [
                                            {
                                              "kind": "song",
                                              "trackId": 2002,
                                              "trackName": "Wait",
                                              "artistName": "M83",
                                              "collectionId": 555,
                                              "collectionName": "Hurry Up, We're Dreaming"
                                            }
                                          ]
                                        }
                                        """.trimIndent(),
                                )
                            }

                            path == "/us/browse" -> MockResponse(code = 200, body = """<script src="/assets/index-1.js"></script>""")
                            path == "/assets/index-1.js" -> MockResponse(code = 200, body = """"$jwt"""")
                            path == "/v1/catalog/us/albums/555" -> {
                                MockResponse(
                                    code = 200,
                                    body =
                                        """
                                        {
                                          "resources": {
                                            "albums": {
                                              "555": {
                                                "attributes": {
                                                  "editorialVideo": {
                                                    "motionSquareVideo1x1": { "video": "$base/hls/sq/master.m3u8" }
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                )
                            }

                            path == "/hls/sq/master.m3u8" -> {
                                MockResponse(
                                    code = 200,
                                    body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=720x720\nsq720.m3u8",
                                )
                            }

                            else -> MockResponse(code = 404)
                        }
                    }
                }

            val service = newService(now)
            assertNull(service.fetchAnimatedCover(title = "Wait", artist = "M83", album = null))
            failSearch.set(false)
            val resolved = service.fetchAnimatedCover(title = "Wait", artist = "M83", album = null)
            assertNotNull(resolved)
            assertEquals("$base/hls/sq/sq720.m3u8", resolved?.url)
            service.dispose()
        }
}

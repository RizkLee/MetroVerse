package com.metrolist.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class PlaylistPanelRendererTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `resolvedVideoRenderer unwraps primary and counterpart wrapper renderers`() {
        val raw =
            """
            {
              "playlistId": "RDAMVMabc",
              "contents": [
                {
                  "playlistPanelVideoWrapperRenderer": {
                    "primaryRenderer": {
                      "playlistPanelVideoRenderer": {
                        "videoId": "seed123",
                        "selected": true,
                        "title": { "runs": [ { "text": "Seed Song" } ] },
                        "lengthText": { "runs": [ { "text": "3:20" } ] },
                        "longBylineText": { "runs": [ { "text": "Artist A" } ] },
                        "thumbnail": { "thumbnails": [ { "url": "https://example.com/a.jpg", "width": 120, "height": 120 } ] },
                        "navigationEndpoint": { "watchEndpoint": { "videoId": "seed123" } }
                      }
                    }
                  }
                },
                {
                  "playlistPanelVideoWrapperRenderer": {
                    "counterpart": [
                      {
                        "counterpartRenderer": {
                          "playlistPanelVideoRenderer": {
                            "videoId": "counter456",
                            "selected": false,
                            "title": { "runs": [ { "text": "Counter Song" } ] },
                            "lengthText": { "runs": [ { "text": "3:21" } ] },
                            "longBylineText": { "runs": [ { "text": "Artist B" } ] },
                            "thumbnail": { "thumbnails": [ { "url": "https://example.com/b.jpg", "width": 120, "height": 120 } ] },
                            "navigationEndpoint": { "watchEndpoint": { "videoId": "counter456" } }
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()

        val renderer = json.decodeFromString(PlaylistPanelRenderer.serializer(), raw)
        assertEquals("seed123", renderer.contents[0].resolvedVideoRenderer()?.videoId)
        assertEquals(true, renderer.contents[0].resolvedVideoRenderer()?.selected)
        assertNotNull(renderer.contents[1].resolvedVideoRenderer())
        assertEquals("counter456", renderer.contents[1].resolvedVideoRenderer()?.videoId)
        assertNull(PlaylistPanelRenderer.Content().resolvedVideoRenderer())
    }
}

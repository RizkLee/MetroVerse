/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.innertube.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongwritersExtractorTest {
    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `extracts songwriters from onResponseReceivedActions dialog and ignores performer or producer`() {
        val response = parse(
            """
            {
              "onResponseReceivedActions": [
                {
                  "openPopupAction": {
                    "popup": {
                      "dismissableDialogRenderer": {
                        "sections": [
                          {
                            "dismissableDialogContentSectionRenderer": {
                              "title": { "runs": [ { "text": "Performed by" } ] },
                              "subtitle": { "runs": [ { "text": "Singer A" } ] }
                            }
                          },
                          {
                            "dismissableDialogContentSectionRenderer": {
                              "title": { "runs": [ { "text": "Written by" } ] },
                              "subtitle": { "runs": [ { "text": "Alice, Bob\nAlice\n , Carol " } ] }
                            }
                          },
                          {
                            "dismissableDialogContentSectionRenderer": {
                              "title": { "runs": [ { "text": "Produced by" } ] },
                              "subtitle": { "runs": [ { "text": "Producer Z" } ] }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("Alice", "Bob", "Carol"),
            SongwritersExtractor.extractSongwriters(response),
        )
    }

    @Test
    fun `supports popup fallback structure and localized writer titles`() {
        val localizedHeadings = listOf(
            "词曲作者" to "Fang Wenshan",
            "詞曲作者" to "Jay Chou",
            "作词" to "Lin Xi",
            "作詞" to "Yonezu Kenshi",
            "작곡" to "IU",
            "Geschrieben von" to "Hans Zimmer",
            "Écrit par" to "Clara Luciani",
            "Авторы" to "Viktor Tsoi",
        )
        for ((heading, author) in localizedHeadings) {
            val response = parse(
                """
                {
                  "popup": {
                    "dismissableDialogRenderer": {
                      "sections": [
                        {
                          "title": { "simpleText": "$heading" },
                          "subtitle": { "simpleText": "$author" }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            )
            assertEquals(listOf(author), SongwritersExtractor.extractSongwriters(response))
        }
    }

    @Test
    fun `excludes metadata provider sections and empty responses`() {
        val response = parse(
            """
            {
              "onResponseReceivedActions": [
                {
                  "openPopupAction": {
                    "popup": {
                      "dismissableDialogRenderer": {
                        "sections": [
                          {
                            "title": "Provided by",
                            "subtitle": "Universal Music"
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertTrue(SongwritersExtractor.extractSongwriters(response).isEmpty())
        assertTrue(SongwritersExtractor.extractSongwriters(parse("{}")).isEmpty())
    }
}

package com.metrolist.music.api

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TranslationResponsePolicyTest {
    @Test
    fun `retry after accepts seconds and surrounding whitespace`() {
        assertEquals(30_000L, parseTranslationRetryAfter(" 30 "))
        assertEquals(0L, parseTranslationRetryAfter("0"))
    }

    @Test
    fun `retry after accepts an HTTP date relative to response time`() {
        val now = Instant.parse("2026-10-08T08:00:00Z").toEpochMilli()
        assertEquals(15_000L, parseTranslationRetryAfter("Thu, 8 Oct 2026 08:00:15 GMT", now))
        assertEquals(0L, parseTranslationRetryAfter("Thu, 8 Oct 2026 07:59:59 GMT", now))
    }

    @Test
    fun `invalid retry after does not create a misleading cooldown`() {
        listOf(null, "", " ", "-1", "+1", "1.5", "tomorrow").forEach {
            assertNull(parseTranslationRetryAfter(it))
        }
    }

    @Test
    fun `large retry after values never overflow into immediate retries`() {
        assertEquals(Long.MAX_VALUE, parseTranslationRetryAfter(Long.MAX_VALUE.toString()))
        assertEquals(Long.MAX_VALUE, parseTranslationRetryAfter("999999999999999999999999999"))
    }

    @Test
    fun `strict subtitle responses reject both missing and extra lines`() {
        listOf(listOf("one"), listOf("one", "two", "three")).forEach { lines ->
            assertThrows(TranslationRequestException::class.java) {
                alignProviderTranslationLines(lines, expectedLineCount = 2, strictLineCount = true)
            }
        }
        assertEquals(
            listOf("one", "two"),
            alignProviderTranslationLines(listOf("one", "two"), 2, strictLineCount = true),
        )
    }

    @Test
    fun `existing lyrics response alignment is preserved`() {
        assertEquals(
            listOf("one", ""),
            alignProviderTranslationLines(listOf("one"), 2, strictLineCount = false),
        )
        assertEquals(
            listOf("one", "two"),
            alignProviderTranslationLines(listOf("one", "two", "three"), 2, strictLineCount = false),
        )
    }
}

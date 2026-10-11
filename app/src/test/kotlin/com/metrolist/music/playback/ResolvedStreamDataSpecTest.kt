package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.metrolist.innertube.InnerTubeXTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ResolvedStreamDataSpecTest {
    @Test
    fun `bounded request preserves media position key and combined headers`() {
        val request = DataSpec.Builder().setUri("https://example.com/unresolved")
            .setPosition(2_048).setKey("song")
            .setHttpRequestHeaders(mapOf("X-Test" to "present")).build()
        val resolved = request.withResolvedStream(
            CachedStreamUrl("https://example.com/resolved", mapOf("User-Agent" to "client"), "IOS", true, 1_024),
        )
        assertEquals(2_048L, resolved.position)
        assertEquals(1_024L, resolved.length)
        assertEquals("song", resolved.key)
        assertEquals(mapOf("X-Test" to "present", "User-Agent" to "client"), resolved.httpRequestHeaders)
    }

    @Test
    fun `short final range is never expanded and regular streams remain unbounded`() {
        val request = DataSpec.Builder().setUri("https://example.com/stream").setPosition(2_048).setLength(100).build()
        assertEquals(100L, request.withResolvedStream(CachedStreamUrl("https://example.com/media", emptyMap(), "IOS", true, 1_024)).length)
        val unknownLength = DataSpec.Builder().setUri("https://example.com/stream").build()
        assertEquals(C.LENGTH_UNSET.toLong(), unknownLength.withResolvedStream(CachedStreamUrl("https://example.com/media", emptyMap(), "VISIONOS")).length)
        assertEquals(1_024L, unknownLength.withResolvedStream(CachedStreamUrl("https://example.com/media", emptyMap(), "IOS", false, 1_024, true)).length)
    }

    @Test
    fun `cached private headers cannot survive account or proxy changes`() {
        val cache = StreamUrlCache()
        val first = InnerTubeXTransport.SessionKey(1, 1)
        cache.put("song", "https://example.com/media", mapOf("Cookie" to "test-session"), "WEB_REMIX", 30, sessionKey = first)
        assertEquals("test-session", cache.getForSession("song", first, emptySet())?.requestHeaders?.get("Cookie"))
        assertNull(cache.getForSession("song", InnerTubeXTransport.SessionKey(1, 2), emptySet()))
        assertNull(cache["song"])
        cache.put("song", "https://example.com/media", emptyMap(), "WEB_REMIX", 30, sessionKey = first)
        assertNull(cache.getForSession("song", InnerTubeXTransport.SessionKey(2, 1), emptySet()))
    }
}

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedAudioContentLengthTest {
    @Test
    fun `range response uses total length rather than returned byte count`() {
        assertEquals(8_000L, resolvedAudioContentLength(206, "bytes 0-0/8000", "1"))
    }

    @Test
    fun `range response with unknown or wrong range does not invent total length`() {
        assertNull(resolvedAudioContentLength(206, "bytes 0-0/*", "1"))
        assertNull(resolvedAudioContentLength(206, "bytes 5-5/8000", "1"))
        assertNull(resolvedAudioContentLength(206, null, "1"))
        assertNull(resolvedAudioContentLength(206, "bytes 0-0/0", "1"))
    }

    @Test
    fun `server ignoring range can declare complete length`() {
        assertEquals(8_000L, resolvedAudioContentLength(200, null, "8000"))
        assertNull(resolvedAudioContentLength(200, null, "0"))
        assertNull(resolvedAudioContentLength(200, null, null))
        assertNull(resolvedAudioContentLength(403, null, "8000"))
    }
}

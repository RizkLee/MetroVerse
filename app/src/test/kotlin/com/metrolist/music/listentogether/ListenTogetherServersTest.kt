package com.metrolist.music.listentogether

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherServersTest {
    @Test
    fun `blank server remains unconfigured`() {
        assertEquals("", normalizeListenTogetherServerUrl("   "))
    }

    @Test
    fun `legacy server migrates to listed official server`() {
        assertEquals(
            ListenTogetherServers.defaultServerUrl,
            normalizeListenTogetherServerUrl("wss://metroserver.meowery.eu/ws"),
        )
    }

    @Test
    fun `listed and custom servers are preserved`() {
        assertEquals(
            ListenTogetherServers.defaultServerUrl,
            normalizeListenTogetherServerUrl(ListenTogetherServers.defaultServerUrl),
        )
        assertEquals(
            "wss://example.test/ws",
            normalizeListenTogetherServerUrl("  wss://example.test/ws  "),
        )
    }
}

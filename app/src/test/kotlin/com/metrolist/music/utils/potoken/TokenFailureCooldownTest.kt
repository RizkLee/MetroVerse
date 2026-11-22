/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.potoken

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenFailureCooldownTest {
    @Test
    fun `failure activates exponential backoff cooldown up to max and resets cleanly`() {
        var clockMs = 1_000L
        val cooldown = TokenFailureCooldown(elapsedMs = { clockMs }, networkScope = { "wifi:validated" })

        assertFalse(cooldown.isCoolingDown("visitor-1", "cookie-a"))

        cooldown.recordFailure("visitor-1", "cookie-a")
        assertTrue(cooldown.isCoolingDown("visitor-1", "cookie-a"))

        clockMs += TokenFailureCooldown.INITIAL_BACKOFF_MS - 1
        assertTrue(cooldown.isCoolingDown("visitor-1", "cookie-a"))

        clockMs += 1
        assertFalse(cooldown.isCoolingDown("visitor-1", "cookie-a"))

        // Second failure doubles backoff to 60s
        cooldown.recordFailure("visitor-1", "cookie-a")
        clockMs += TokenFailureCooldown.INITIAL_BACKOFF_MS
        assertTrue(cooldown.isCoolingDown("visitor-1", "cookie-a"))

        clockMs += TokenFailureCooldown.INITIAL_BACKOFF_MS
        assertFalse(cooldown.isCoolingDown("visitor-1", "cookie-a"))

        cooldown.reset()
        assertFalse(cooldown.isCoolingDown("visitor-1", "cookie-a"))
    }

    @Test
    fun `cooldown clears when cookie visitorData or network scope changes`() {
        var clockMs = 10_000L
        var network = "wifi:validated"
        val cooldown = TokenFailureCooldown(elapsedMs = { clockMs }, networkScope = { network })

        cooldown.recordFailure("visitor-1", "cookie-a")
        assertTrue(cooldown.isCoolingDown("visitor-1", "cookie-a"))

        // Different cookie resets cooldown
        assertFalse(cooldown.isCoolingDown("visitor-1", "cookie-b"))

        // Re-record on cookie-b and verify different visitorData resets
        cooldown.recordFailure("visitor-1", "cookie-b")
        assertTrue(cooldown.isCoolingDown("visitor-1", "cookie-b"))
        assertFalse(cooldown.isCoolingDown("visitor-2", "cookie-b"))

        // Re-record and verify network change clears
        cooldown.recordFailure("visitor-2", "cookie-b")
        assertTrue(cooldown.isCoolingDown("visitor-2", "cookie-b"))
        network = "cellular:validated"
        assertFalse(cooldown.isCoolingDown("visitor-2", "cookie-b"))
    }
}

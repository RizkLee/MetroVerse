/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test
    fun `redacts cookies auth headers api keys signed urls private rss userinfo and ips`() {
        val raw = buildString {
            appendLine("Cookie: SAPISID=secretvalue; __Secure-3PAPISID=abc")
            appendLine("Authorization: Bearer super-secret-bearer-token")
            appendLine("Authorization: SAPISIDHASH 1700000_abcdef012345")
            appendLine("key=sk-or-v1-1234567890abcdef api_key=my_deepl_secret")
            appendLine("https://rr1---sn-abc.googlevideo.com/videoplayback?expire=999&sig=secretSig&pot=secretPot")
            appendLine("https://user:pass@private.podcast.example.com/1234567890abcdefghijklmnop/feed.rss?token=xyz")
            appendLine("Connected to 192.168.1.42 and 2001:0db8:85a3:0000:0000:8a2e:0370:7334")
            appendLine("r8-map-id-deadbeefcafebabe1234567890abcdef1234567890")
        }

        val redacted = redactDiagnosticText(raw)

        assertFalse(redacted.contains("secretvalue"))
        assertFalse(redacted.contains("super-secret-bearer-token"))
        assertFalse(redacted.contains("1700000_abcdef012345"))
        assertFalse(redacted.contains("sk-or-v1-1234567890abcdef"))
        assertFalse(redacted.contains("my_deepl_secret"))
        assertFalse(redacted.contains("secretSig"))
        assertFalse(redacted.contains("secretPot"))
        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("1234567890abcdefghijklmnop"))
        assertFalse(redacted.contains("token=xyz"))
        assertFalse(redacted.contains("192.168.1.42"))
        assertFalse(redacted.contains("85a3:0000"))
        assertTrue(redacted.contains("<redacted>"))
        assertTrue(redacted.contains("<redacted-ip>"))
        assertTrue(redacted.contains("r8-map-id-deadbeefcafebabe1234567890abcdef1234567890"))
    }
}

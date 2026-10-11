package com.metrolist.music.utils

import androidx.datastore.preferences.core.preferencesOf
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.music.constants.StreamSourceAndroidVRKey
import com.metrolist.music.constants.StreamSourceTVHTML5Key
import com.metrolist.music.constants.StreamSourceVisionOSKey
import com.metrolist.music.constants.StreamSourceWebCreatorKey
import com.metrolist.music.constants.StreamSourceWebRemixKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSourcePreferencesTest {
    @Test
    fun `missing preferences retain automatic client selection`() {
        assertTrue(preferencesOf().disabledStreamClients().isEmpty())
    }

    @Test
    fun `native switch disables every version of the same InnerTubeX client`() {
        val excluded = preferencesOf(StreamSourceVisionOSKey to false, StreamSourceAndroidVRKey to false)
            .disabledStreamClients()
        assertEquals(setOf("VISIONOS", "ANDROID_VR"), excluded)
        val selected = ContentAwareFallbackStrategy().selectClients(request(excluded)).candidates
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.none { it.client.clientName in excluded })
    }

    @Test
    fun `TV switch also disables embedded and downgraded TV clients`() {
        val excluded = preferencesOf(StreamSourceTVHTML5Key to false).disabledStreamClients()
        assertTrue("TVHTML5" in excluded)
        assertTrue("TVHTML5_SIMPLY" in excluded)
        assertTrue("TVHTML5_SIMPLY_EMBEDDED_PLAYER" in excluded)
        assertTrue(PlaybackClientCatalog.manifests.filter { it.client.clientName.startsWith("TVHTML5") }
            .all { it.client.clientName in excluded })
    }

    @Test
    fun `web settings reach the extractor exclusion contract without disabling unrelated sources`() {
        val excluded = preferencesOf(StreamSourceWebRemixKey to false, StreamSourceWebCreatorKey to false)
            .disabledStreamClients()
        assertEquals(setOf("WEB_REMIX", "WEB_CREATOR"), excluded)
        val selected = ContentAwareFallbackStrategy().selectClients(request(excluded)).candidates
        assertTrue(selected.isNotEmpty())
        assertFalse(selected.any { it.client.clientName == "WEB_REMIX" || it.client.clientName == "WEB_CREATOR" })
    }

    private fun request(excluded: Set<String>) = ClientSelectionRequest(
        hints = ContentHints(),
        authenticated = true,
        excludedClients = excluded,
        javaScriptRuntimeAvailable = true,
        webViewAvailable = true,
        availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
    )
}

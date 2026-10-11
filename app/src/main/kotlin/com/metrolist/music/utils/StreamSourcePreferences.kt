/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import androidx.datastore.preferences.core.Preferences
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.music.constants.StreamSourceAndroidVRKey
import com.metrolist.music.constants.StreamSourceTVHTML5Key
import com.metrolist.music.constants.StreamSourceVisionOSKey
import com.metrolist.music.constants.StreamSourceWebCreatorKey
import com.metrolist.music.constants.StreamSourceWebRemixKey

internal fun Preferences.disabledStreamClients(): Set<String> = buildSet {
    if (this@disabledStreamClients[StreamSourceWebRemixKey] == false) add("WEB_REMIX")
    if (this@disabledStreamClients[StreamSourceAndroidVRKey] == false) add("ANDROID_VR")
    if (this@disabledStreamClients[StreamSourceVisionOSKey] == false) add("VISIONOS")
    if (this@disabledStreamClients[StreamSourceWebCreatorKey] == false) add("WEB_CREATOR")
    if (this@disabledStreamClients[StreamSourceTVHTML5Key] == false) {
        // The existing TV switch also covers InnerTubeX's embedded and downgraded TV profiles.
        addAll(PlaybackClientCatalog.manifests.map { it.client.clientName }.filter { it.startsWith("TVHTML5") })
    }
}

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.player

import com.metrolist.music.podcast.transcript.SubtitleCue
import com.metrolist.music.podcast.transcript.subtitleCueFingerprint

internal data class KeyedSubtitleCue(val key: String, val cue: SubtitleCue)

internal class SubtitleCueIdentityCache(
    private val fingerprint: (SubtitleCue) -> String = ::subtitleCueFingerprint,
) {
    private var fingerprints: Map<SubtitleCue, String> = emptyMap()

    fun keyedCues(cues: List<SubtitleCue>): List<KeyedSubtitleCue> {
        val currentFingerprints = HashMap<SubtitleCue, String>(cues.size)
        val occurrences = mutableMapOf<String, Int>()
        val result = cues.map { cue ->
            val cueFingerprint = currentFingerprints.getOrPut(cue) {
                fingerprints[cue] ?: fingerprint(cue)
            }
            val occurrence = occurrences[cueFingerprint] ?: 0
            occurrences[cueFingerprint] = occurrence + 1
            // Exact duplicates need distinct list keys; earlier cues with different timing do not change them.
            KeyedSubtitleCue("$cueFingerprint:$occurrence", cue)
        }
        fingerprints = currentFingerprints
        return result
    }
}

internal fun retainSubtitleCueSelection(
    cues: List<KeyedSubtitleCue>,
    selectedKeys: Set<String>,
): Set<String> =
    if (selectedKeys.isEmpty()) emptySet()
    else cues.mapNotNullTo(mutableSetOf()) { item -> item.key.takeIf { it in selectedKeys } }

internal fun selectedSubtitleCues(
    cues: List<KeyedSubtitleCue>,
    selectedKeys: Set<String>,
): List<SubtitleCue> =
    if (selectedKeys.isEmpty()) emptyList()
    else cues.mapNotNull { item -> item.cue.takeIf { item.key in selectedKeys } }
        .sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })

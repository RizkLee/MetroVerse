/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import androidx.annotation.Keep

@Keep
internal data class MoonshineLine(
    val id: Long,
    val text: String,
    val startTimeSeconds: Float,
    val durationSeconds: Float,
    val complete: Boolean,
)

/** Only this bridge owns native handles; operations on one handle are serialized. */
@Keep
internal object MoonshineNative {
    init {
        System.loadLibrary("metroverse_moonshine")
    }

    external fun create(modelDirectory: String, modelArchitecture: Int): Int
    external fun createStream(handle: Int): Int
    external fun acceptAudio(handle: Int, stream: Int, samples: FloatArray): Array<MoonshineLine>
    external fun finishStream(handle: Int, stream: Int): Array<MoonshineLine>
    external fun freeStream(handle: Int, stream: Int)
    external fun close(handle: Int)
}

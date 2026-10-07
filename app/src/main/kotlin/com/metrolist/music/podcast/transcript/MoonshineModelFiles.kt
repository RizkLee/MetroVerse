/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.podcast.transcript

internal object MoonshineModelFiles {
    const val DIRECTORY = "moonshine-small-streaming-en-26-08-21"
    private const val BASE_URL = "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21"

    // The supplied files matched v0.1.5's published sizes and CRC32C values before these SHA-256 pins were recorded.
    val artifacts = listOf(
        artifact("adapter.ort", 2_870_368, "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762"),
        artifact("cross_kv.ort", 5_356_536, "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6"),
        artifact("decoder_kv.ort", 81_878_600, "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b"),
        artifact("encoder.ort", 44_148_576, "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993"),
        artifact("frontend.model.ort", 26_944, "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f"),
        artifact("frontend.weights.ort", 7_769_464, "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338"),
        artifact("streaming_config.json", 512, "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a"),
        artifact("tokenizer.bin", 249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d"),
    )

    private fun artifact(name: String, bytes: Long, sha256: String) =
        AsrModelArtifact(name, bytes, sha256, listOf("$BASE_URL/$name"))
}

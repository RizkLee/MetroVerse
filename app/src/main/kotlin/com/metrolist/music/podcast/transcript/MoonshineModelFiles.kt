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


    fun artifacts(language: AsrLanguage): List<AsrModelArtifact> = when (language) {
        AsrLanguage.ENGLISH -> artifacts
        AsrLanguage.GERMAN -> listOf(
            artifact(language, "adapter.ort", 2869296, "be438a3eb12ba3697a9e474d20f899e52d8e27b43a6fc4c4c809d2a099646744"),
            artifact(language, "cross_kv.ort", 5358752, "0c329cbf001bebbb9e34835e95676b9eb29e010405da46f65af511b8da16620e"),
            artifact(language, "decoder_kv.ort", 61314512, "7da8ac9a1da2a486158ccde88c756fd876bf7f11ef1c2ba14b80e8edcd30028c"),
            artifact(language, "encoder.ort", 44358376, "f31ef482f940e23af87906aeec986bf2e0a4862cad577bbc745264caab5a1858"),
            artifact(language, "frontend.model.ort", 26776, "a93453223d93aa41d21691568cde3a01125fe00710a5f7004a825e1a322d3025"),
            artifact(language, "frontend.weights.ort", 7769280, "8b417e2e0ee762e34d7163d24383823bb8bd1d037fb6966c32b3f86a3de4961f"),
            artifact(language, "streaming_config.json", 512, "12d16c7f5ea6734d197b79baf47914ea7e996d6fca8d303a19aca10d0617cecc"),
            artifact(language, "tokenizer.bin", 103319, "5725ad3771a1339802f8cebeda0fa8f7723f5282b1ee726c2544774d7399b9a7"),
        )
        AsrLanguage.SPANISH -> listOf(
            artifact(language, "adapter.ort", 2869296, "04b54114c8aab534222922640f7ca0882948ff9f6ed76777f6e184e55a8e8b15"),
            artifact(language, "cross_kv.ort", 5358752, "4bfd0a641d72ccdae22751f86bbb2e25ff70c1fba4f81213f2415a1accff9618"),
            artifact(language, "decoder_kv.ort", 61314512, "5b77c3d6baf801ef925a5bc54d7eb3db0c35ebbb86f8eaf5390d7f5bb42fef37"),
            artifact(language, "encoder.ort", 44358376, "a9b8d6d5d9348d0e319cceffdb0196ef622df8e4e9f3fb4797dcd8dfafd50857"),
            artifact(language, "frontend.model.ort", 26776, "69c76287f49db365aa278d4908ec450e69ca1b4bfb7e836159d963d623ee0c13"),
            artifact(language, "frontend.weights.ort", 7769280, "2f5a0eb5f3004c9447810d74274a352746319a32144e31d95ef410f425b84dda"),
            artifact(language, "streaming_config.json", 512, "12d16c7f5ea6734d197b79baf47914ea7e996d6fca8d303a19aca10d0617cecc"),
            artifact(language, "tokenizer.bin", 102888, "5fbb7d4314dcb18e03c5f975609e4a4cd572b22b01d2b2accb1b3e6830696f36"),
        )
        AsrLanguage.JAPANESE -> listOf(
            artifact(language, "adapter.ort", 2869296, "edcf20b9d0bc0a92bc0ddf37d8b96599de35a6cbee970368ed74c774b055e546"),
            artifact(language, "cross_kv.ort", 5358752, "fbd18ec7b9514391d7121f4d4abec1be05a60e94a0f174061428380bf772c3e7"),
            artifact(language, "decoder_kv.ort", 61314512, "1246d81eeb96df05d0163dcd48ce271a061b01aa3aea047239fbde22485248be"),
            artifact(language, "encoder.ort", 44358376, "1da7cc82cb91d30ad9dc4e338fb80e7a2336b2c8ad634b826419222698fbe3a5"),
            artifact(language, "frontend.model.ort", 31208, "de65092295f011a39ee569f03d073e3564d94252efd74ebde16d720bcc43e38b"),
            artifact(language, "frontend.weights.ort", 7769288, "4e89578a495a6c4dd5632e7acaf2fc38841e6831738c2702ef10f873125a14ec"),
            artifact(language, "streaming_config.json", 512, "12d16c7f5ea6734d197b79baf47914ea7e996d6fca8d303a19aca10d0617cecc"),
            artifact(language, "tokenizer.bin", 101836, "9f2599f20c3a9b03d79dbe122e443647c6589ce8e5c73e65e5c5d6f73485d5de"),
        )
    }


    private fun artifact(language: AsrLanguage, name: String, bytes: Long, sha256: String): AsrModelArtifact {
        val baseUrl = "https://download.moonshine.ai/model/small-streaming-${language.code}/quantized_${language.modelDate}"
        return AsrModelArtifact(name, bytes, sha256, listOf("$baseUrl/$name"))
    }

    private fun artifact(name: String, bytes: Long, sha256: String) =
        AsrModelArtifact(name, bytes, sha256, listOf("$BASE_URL/$name"))
}

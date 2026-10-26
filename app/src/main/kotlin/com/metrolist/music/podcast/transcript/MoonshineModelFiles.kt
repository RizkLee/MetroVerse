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
        AsrLanguage.MANDARIN -> listOf(
            artifact(language, "adapter.ort", 1318472, "84d1bed88310f5b6dd452203bcaffb765f017a7d06da8d84a898235ef5c90e12"),
            artifact(language, "cross_kv.ort", 1288120, "22c2261f3120b766a91deceb4f32ca0bef0a8f2b181509ad0dc9fe6adbd0c23a"),
            artifact(language, "decoder_kv.ort", 19717336, "5caf9e4e9eaa39b93122338bfbad2917630afc51293f6229b935ae7df28bed33"),
            artifact(language, "encoder.ort", 7772792, "4270ca0e1ecd10e78d658b08b91d81c7f913511177e1ad461c8a9d9d0903503d"),
            artifact(language, "frontend.model.ort", 27608, "d27a9c994b43fa6568121f502e8b99d511ffd0425d4090ffddcc82c9a5667971"),
            artifact(language, "frontend.weights.ort", 2090728, "ac56e8ee7780e1f254ee26f2d9c9a3b3c6cfd2f6822cb3944924ae321f86dc50"),
            artifact(language, "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
            artifact(language, "tokenizer.bin", 74587, "f172da002a818b9968d5c7a995cc7c51acf2168331ab5f914b5b0860ceab8236"),
        )
        AsrLanguage.ARABIC -> listOf(
            artifact(language, "adapter.ort", 1318472, "9c1278a338b4ff268fc5741be01446d56ca9cba9ff5f4a75c5e1b2c2e2739361"),
            artifact(language, "cross_kv.ort", 1288120, "e317af289233894f49da429d4561150c2f61ca86c1b9ca91c6edd3fb4fdeecb5"),
            artifact(language, "decoder_kv.ort", 19717336, "5c9a2bf2d9fc543fd4596b3ab4ab8d09f791aac5682eb1379fd8d59e34237db0"),
            artifact(language, "encoder.ort", 7772792, "6785ca9932d59265375249a8f3bc62f8333fb4dee6ab7db8dfd6f1daef66f59e"),
            artifact(language, "frontend.model.ort", 23176, "04c552e66efe3f7206d0a69779864f93bf3cfaf2fa595c9ce5101c8e3854a414"),
            artifact(language, "frontend.weights.ort", 2093280, "6cc4d70efffe757452996574d11065e7dac6a7ff0a9be3e2fec1d0266b062b0e"),
            artifact(language, "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
            artifact(language, "tokenizer.bin", 135726, "3acea39a89cdf44fa11f10bfdd7d05d9015d901999f8c8fa18988f19dcc6b267"),
        )
        AsrLanguage.TAGALOG -> listOf(
            artifact(language, "adapter.ort", 1318472, "a47413f3bab023770a82c3d2426edaac2571d4b6ef11f1de4f5ef04fe8921815"),
            artifact(language, "cross_kv.ort", 1288120, "d449d3b4969cac1c0c07536c5c339bda1d8fa2768cebbfc81f4487f365a933bd"),
            artifact(language, "decoder_kv.ort", 19717336, "7c34e8da5f52d98883a5a47ea5951fd33b2cdbde871189e623baf4f3accf96fc"),
            artifact(language, "encoder.ort", 7772792, "6613d539cebdbfc78d492400a4a53042e1eeb2065c9efb2569e08eda2b716988"),
            artifact(language, "frontend.model.ort", 27608, "a94955c8ef260db0f9cc04c96ab0d35fd59bab8d61acbb1b8b466dad638e0a87"),
            artifact(language, "frontend.weights.ort", 2093288, "83a518fdf989752c52338031fd997d23f84ae9c1c45945a30cac91dcaba93518"),
            artifact(language, "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
            artifact(language, "tokenizer.bin", 91356, "f1d4f9f04a3943a535cf3ad53329ae465237dd4a11bd38c5a9db47a9164bf892"),
        )
        AsrLanguage.VIETNAMESE -> listOf(
            artifact(language, "adapter.ort", 1318472, "8351344095ce2e7e8dca7a932c102c7976fd5031d9f50946c05fe4e8ea520cab"),
            artifact(language, "cross_kv.ort", 1288120, "0a245cc6b617f9c3a2aebe3ba5e4e12dc6118750fb7e478ccb95fe629abe56cf"),
            artifact(language, "decoder_kv.ort", 19717336, "b398871f7668c667f9853758e1bd5ffb77d1ddf72f90ae73ecd4b1f05b3df1d9"),
            artifact(language, "encoder.ort", 7772792, "4774270b09a51af0bc7d7a6f5d687aa832ef6ca049ea965ba0ca23f965f3f3dd"),
            artifact(language, "frontend.model.ort", 23176, "fb8c8ae3cbc72f28e56a6f7577440b4e41e41a86b183d50ebd3d35215b712a7d"),
            artifact(language, "frontend.weights.ort", 2093280, "beb51a94a57cb33ea740c23d7841ca2855e39fd9df7468a407dfd5eea471d8ca"),
            artifact(language, "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
            artifact(language, "tokenizer.bin", 95323, "004723b832413eca43dc95f5c1e14311fa78206f81e6bf99ce0e561c657ab209"),
        )
    }


    fun artifacts(model: AsrModel): List<AsrModelArtifact> {
        if (model.variant == model.language.modelVariant) return artifacts(model.language)
        return when (model.language) {
            AsrLanguage.ENGLISH -> listOf(
                artifact(model, "adapter.ort", 1319664, "22ecc949e146c49667fda28d102d4e30749a107dc88a396292aa8f277ef1347c"),
                artifact(model, "cross_kv.ort", 1287544, "143a36667b8d05fd9d04e8c337b7ee121f37ef299aea6b3d82bdb3d3401950b4"),
                artifact(model, "decoder_kv.ort", 32583720, "8852553f312adb6c9aa4d17418015049b30f412209ee569d336548c0044627de"),
                artifact(model, "encoder.ort", 7675440, "a8414e1a5dedf9f2093d7680601dd8a9b0433e7020260eafe0e370ead91134ca"),
                artifact(model, "frontend.model.ort", 23344, "5121b561417b638afce0c6c31b760e37c93cf97f80d9b0031aad1fe7b6f25d61"),
                artifact(model, "frontend.weights.ort", 2093464, "217da24ac6f522ebf02da8ef288e77d1ac68d50d4a6821433182e4fbf4204bbd"),
                artifact(model, "streaming_config.json", 509, "74fe5ddebd63b17caf59e8a3b18c17547ff7bce1642050edbb1c3962674f8950"),
                artifact(model, "tokenizer.bin", 249974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d"),
            )
            AsrLanguage.GERMAN -> listOf(
                artifact(model, "adapter.ort", 1318472, "23b91142a35cfbc533a7f03ad8387d0b6d32856b465c6fe7a14ede44a254a345"),
                artifact(model, "cross_kv.ort", 1288120, "25e6d9d4f16569c2ede7e23a381fe7c60cc4b0f141adc88580557d4530634f17"),
                artifact(model, "decoder_kv.ort", 19717336, "768b1464074fc4fb7327e8b863fc13dcd12cb7bbe8d8e9428fa7292f7fd9ca6e"),
                artifact(model, "encoder.ort", 7772792, "74610b818058e396f4b9d9ffd730cf8e36772122464d989dd3011dfa76bd6baf"),
                artifact(model, "frontend.model.ort", 23176, "4445bb4c2f41b0dd993999e06dc9367a0dde0d42a2e777116e7d93be102dc43d"),
                artifact(model, "frontend.weights.ort", 2093280, "233fda61d583403bbf13ea71b6ba2335fdf57eac71be85a97dcbb90a6c2f4186"),
                artifact(model, "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
                artifact(model, "tokenizer.bin", 103319, "5725ad3771a1339802f8cebeda0fa8f7723f5282b1ee726c2544774d7399b9a7"),
            )
            AsrLanguage.SPANISH -> listOf(
                artifact(model, "adapter.ort", 1318472, "2efd97cd2ee7d89578dd1d429a849be9fe37ac81dd9c4e1d31c8a2e7f85d43e6"),
                artifact(model, "cross_kv.ort", 1288120, "9e268b1b3bb0f9ff79bca6e76055d41298de0a5ba52505ee04907a2642efa3ae"),
                artifact(model, "decoder_kv.ort", 19717336, "7adf56b982df98e385d71823b9a40cf9296ae3a746efcf03f3d7bb4604032db0"),
                artifact(model, "encoder.ort", 7772792, "ec9eb43a01fbfe242a2c6a86050cffe4a31050e1c9f084e98560d6c100004505"),
                artifact(model, "frontend.model.ort", 23176, "9d19434b861de0c1d2aff52556911ae565cf3212ebe6d7f797c76c5c5066c2fc"),
                artifact(model, "frontend.weights.ort", 2093280, "5e4d5471cd5d984d4b94a603d861539ebf1dc72de11b0cf13f38316725d5ce48"),
                artifact(model, "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
                artifact(model, "tokenizer.bin", 102888, "5fbb7d4314dcb18e03c5f975609e4a4cd572b22b01d2b2accb1b3e6830696f36"),
            )
            AsrLanguage.JAPANESE -> listOf(
                artifact(model, "adapter.ort", 1318472, "25f302e6203028e487d1b1f93db6fe5baf587373498062190160de70e0c4ae58"),
                artifact(model, "cross_kv.ort", 1288120, "170ee70aeb3c82fd067193add0d1a8619cd16d4de80a5e0239dac12d956e8575"),
                artifact(model, "decoder_kv.ort", 19717336, "ccc047610727f9fe039212a0b2370a7f970e80776376a229181a76998ff5af20"),
                artifact(model, "encoder.ort", 7772792, "6420c180be55732d10db7a85f8966237bbbeee3b47addab4790ed81c22dde2cf"),
                artifact(model, "frontend.model.ort", 27608, "c6fdee4c81d421617b9571e43cf952dc1e7e4f45d0cb1dbf13104ee8af4ffd45"),
                artifact(model, "frontend.weights.ort", 2093288, "673a7177f030da9ce948daa1af44ec5425b6f0825348aacb80f6a01c18e42a07"),
                artifact(model, "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
                artifact(model, "tokenizer.bin", 101836, "9f2599f20c3a9b03d79dbe122e443647c6589ce8e5c73e65e5c5d6f73485d5de"),
            )
            else -> error("Unsupported model variant")
        }
    }

    private fun artifact(model: AsrModel, name: String, bytes: Long, sha256: String): AsrModelArtifact {
        val baseUrl = "https://download.moonshine.ai/model/${model.variant.pathName}-${model.language.code}/quantized_${model.language.modelDate}"
        return AsrModelArtifact(name, bytes, sha256, listOf("$baseUrl/$name"))
    }

    private fun artifact(language: AsrLanguage, name: String, bytes: Long, sha256: String): AsrModelArtifact {
        val baseUrl = "https://download.moonshine.ai/model/${language.modelVariant.pathName}-${language.code}/quantized_${language.modelDate}"
        return AsrModelArtifact(name, bytes, sha256, listOf("$baseUrl/$name"))
    }

    private fun artifact(name: String, bytes: Long, sha256: String) =
        AsrModelArtifact(name, bytes, sha256, listOf("$BASE_URL/$name"))
}

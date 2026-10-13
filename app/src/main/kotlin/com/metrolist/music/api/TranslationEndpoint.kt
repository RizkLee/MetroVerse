/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Complete only a server root or an API root; explicit custom routes remain authoritative. */
fun completeTranslationUrl(value: String): String {
    val url = value.trim().toHttpUrl()
    val path = url.encodedPath.trimEnd('/')
    val suffix = when {
        path.isEmpty() -> when (url.host) {
            "api.z.ai", "open.bigmodel.cn" -> "/api/paas/v4/chat/completions"
            "api.perplexity.ai" -> "/chat/completions"
            "openrouter.ai" -> "/api/v1/chat/completions"
            else -> "/v1/chat/completions"
        }
        path.matches(Regex("(?:/.*)?/v\\d+(?:beta\\d*)?")) -> "$path/chat/completions"
        else -> return url.toString()
    }
    return url.newBuilder().encodedPath(suffix).build().toString()
}

internal fun JSONObject.configureTranslationModel(url: String, model: String) {
    val host = url.toHttpUrl().host
    when (host) {
        "api.deepseek.com" -> put("thinking", JSONObject().put("type", "disabled"))
        "api.z.ai", "open.bigmodel.cn" -> {
            if (model.startsWith("glm-5.")) {
                put("thinking", JSONObject().put("type", "enabled"))
                put("reasoning_effort", "low")
                put("max_tokens", 16_000)
            } else put("thinking", JSONObject().put("type", "disabled"))
        }
        "api.moonshot.ai", "api.moonshot.cn" -> {
            // K2.6 rejects arbitrary temperature values, including our usual 0.3.
            remove("temperature")
            if (model == "kimi-k3") {
                put("reasoning_effort", "low")
                put("max_tokens", 16_000)
            }
            if (model == "kimi-k2.6") {
                put("thinking", JSONObject().put("type", "disabled"))
                put("temperature", 0.6)
            }
        }
        "api.minimax.io", "api.minimaxi.com" -> {
            if (model == "MiniMax-M3") put("thinking", JSONObject().put("type", "disabled"))
            put("reasoning_split", true)
        }
        "api.siliconflow.cn", "api.siliconflow.com" -> {
            if (model == "Qwen/Qwen3-8B" || model == "Qwen/Qwen3-32B") put("enable_thinking", false)
        }
    }
}

data class TranslationProvider(val endpoint: String, val models: List<String>, val documentation: String)

/** IDs are published API model names; users can still enter a model available to their account. */
val additionalTranslationProviders = linkedMapOf(
    "DeepSeek" to TranslationProvider(
        "https://api.deepseek.com/v1/chat/completions",
        listOf("deepseek-flash", "deepseek-v4-pro"),
        "https://api-docs.deepseek.com/zh-cn/",
    ),
    "Z.ai" to TranslationProvider(
        "https://api.z.ai/api/paas/v4/chat/completions",
        listOf("glm-5.3-flash", "glm-5.3-flashx", "glm-5.3"),
        "https://docs.z.ai/guides/overview/quick-start",
    ),
    "MiniMax" to TranslationProvider(
        "https://api.minimax.io/v1/chat/completions",
        listOf("MiniMax-M3"),
        "https://platform.minimax.io/docs/api-reference/text-openai-api",
    ),
    "Kimi" to TranslationProvider(
        "https://api.moonshot.ai/v1/chat/completions",
        listOf("kimi-k2.6", "kimi-k3"),
        "https://platform.kimi.ai/docs/api/chat",
    ),
    "SiliconFlow" to TranslationProvider(
        "https://api.siliconflow.cn/v1/chat/completions",
        listOf("Qwen/Qwen3-8B", "Qwen/Qwen3-32B", "deepseek-ai/DeepSeek-V4-Flash"),
        "https://docs.siliconflow.cn/en/usercases/use-siliconcloud-in-translate-js",
    ),
    "ModelScope" to TranslationProvider(
        "https://api-inference.modelscope.cn/v1/chat/completions",
        listOf("mistralai/Mistral-Large-Instruct-2407", "Qwen/Qwen3.5-27B", "Qwen/Qwen3.8-27B"),
        "https://modelscope.cn/docs/model-service/API-Inference/intro",
    ),
)

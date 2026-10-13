/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.api

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TranslationEndpointTest {
    @Test fun `server roots API roots and full routes are completed once`() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", completeTranslationUrl(" https://api.deepseek.com/ "))
        assertEquals("https://example.org/v1/chat/completions", completeTranslationUrl("https://example.org/v1/"))
        assertEquals("https://example.org/api/v1/chat/completions", completeTranslationUrl("https://example.org/api/v1"))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", completeTranslationUrl("https://openrouter.ai"))
        val endpoint = "https://api.deepseek.com/v1/chat/completions"
        assertEquals(endpoint, completeTranslationUrl(completeTranslationUrl(endpoint)))
    }
    @Test fun `custom paths query values and local servers are preserved`() {
        listOf("https://example.org/proxy/translate?region=eu", "https://example.org/v1/chat/completions?deployment=one", "http://127.0.0.1:8080/custom").forEach {
            assertEquals(it, completeTranslationUrl(it))
        }
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", completeTranslationUrl("http://127.0.0.1:8080"))
    }
    @Test fun `Z ai roots keep their actual API prefix`() {
        assertEquals("https://api.z.ai/api/paas/v4/chat/completions", completeTranslationUrl("https://api.z.ai"))
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", completeTranslationUrl("https://open.bigmodel.cn/api/paas/v4/"))
    }
    @Test fun `Kimi parameters follow fixed temperature and thinking constraints`() {
        val body = JSONObject().put("temperature", 0.3)
        body.configureTranslationModel("https://api.moonshot.ai/v1/chat/completions", "kimi-k2.6")
        assertEquals(0.6, body.getDouble("temperature"), 0.0)
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        body.configureTranslationModel("https://api.moonshot.ai/v1/chat/completions", "kimi-k3")
        assertFalse(body.has("temperature"))
        assertEquals("low", body.getString("reasoning_effort"))
    }
    @Test fun `GLM 5 cannot disable thinking while DeepSeek can`() {
        val body = JSONObject()
        body.configureTranslationModel("https://api.z.ai/api/paas/v4/chat/completions", "glm-5.3-flash")
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("low", body.getString("reasoning_effort"))
        body.configureTranslationModel("https://api.deepseek.com/v1/chat/completions", "deepseek-flash")
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }
    @Test fun `MiniMax separates reasoning and does not disable mandatory thinking models`() {
        val body = JSONObject()
        body.configureTranslationModel("https://api.minimax.io/v1/chat/completions", "MiniMax-M3")
        assertTrue(body.getBoolean("reasoning_split"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        val other = JSONObject()
        other.configureTranslationModel("https://api.minimax.io/v1/chat/completions", "MiniMax-M3.1-Flash-Preview")
        assertFalse(other.has("thinking"))
    }
    @Test fun `ModelScope presets come from its actual public serving catalog`() {
        val snapshot = JSONObject(javaClass.getResource("/translation-model-catalog/modelscope-2026-10-13.json")!!.readText())
        val models = snapshot.getJSONArray("models")
        val available = (0 until models.length()).map { models.getString(it) }.toSet()
        assertTrue(available.containsAll(additionalTranslationProviders.getValue("ModelScope").models))
    }

    @Test fun `all new provider presets use full endpoints`() {
        assertEquals(6, additionalTranslationProviders.size)
        additionalTranslationProviders.values.forEach {
            assertEquals(it.endpoint, completeTranslationUrl(it.endpoint))
            assertTrue(it.models.isNotEmpty())
        }
    }
}

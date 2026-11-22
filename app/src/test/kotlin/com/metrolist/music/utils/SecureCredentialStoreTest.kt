/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.VisitorDataKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureCredentialStoreTest {

    @Test
    fun `encrypts decrypts non-empty and empty strings with randomized iv`() {
        val prefs = InMemorySharedPreferences()
        val keyAccess = FakeKeyAccess()
        val storage = SecureCredentialStorage(prefs, keyAccess)

        assertTrue(
            storage.putBatch(
                mapOf(
                    Pair(InnerTubeCookieKey, "SAPISID=abc123; SID=xyz"),
                    Pair(OpenRouterApiKey, ""),
                ),
            ),
        )

        val firstCipher = prefs.getString(InnerTubeCookieKey.name, null)!!
        assertTrue(firstCipher.startsWith("v1:"))
        assertFalse(firstCipher.contains("SAPISID"))

        // Second write with identical plaintext must produce a different random IV / ciphertext
        assertTrue(storage.putBatch(mapOf(Pair(InnerTubeCookieKey, "SAPISID=abc123; SID=xyz"))))
        val secondCipher = prefs.getString(InnerTubeCookieKey.name, null)!!
        assertNotEquals(firstCipher, secondCipher)

        // Reload fresh instance from disk prefs
        val reloaded = SecureCredentialStorage(prefs, keyAccess)
        assertEquals("SAPISID=abc123; SID=xyz", reloaded.get(InnerTubeCookieKey))
        assertEquals("", reloaded.get(OpenRouterApiKey))
        assertNull(reloaded.get(DeeplApiKey))
    }

    @Test
    fun `aad prevents transplanting ciphertext across keys and cleans tampered entries`() {
        val prefs = InMemorySharedPreferences()
        val keyAccess = FakeKeyAccess()
        val writer = SecureCredentialStorage(prefs, keyAccess)

        writer.putBatch(mapOf(Pair(OpenRouterApiKey, "sk-openrouter-secret")))
        val stolenCipher = prefs.getString(OpenRouterApiKey.name, null)!!

        // Transplant OpenRouter ciphertext into DeeplApiKey slot + tamper another key
        prefs.edit()
            .putString(DeeplApiKey.name, stolenCipher)
            .putString(LastFMSessionKey.name, "v1:notvalidbase64!!!")
            .commit()

        val reader = SecureCredentialStorage(prefs, keyAccess)
        assertEquals("sk-openrouter-secret", reader.get(OpenRouterApiKey))
        assertNull(reader.get(DeeplApiKey))
        assertNull(reader.get(LastFMSessionKey))
        assertFalse(prefs.contains(DeeplApiKey.name))
        assertFalse(prefs.contains(LastFMSessionKey.name))
    }

    @Test
    fun `rotates invalidated key on write and preserves existing cached credentials`() {
        val prefs = InMemorySharedPreferences()
        val keyAccess = FakeKeyAccess()
        val storage = SecureCredentialStorage(prefs, keyAccess)

        storage.putBatch(mapOf(Pair(VisitorDataKey, "visitor-initial")))
        keyAccess.throwInvalidatedOnNextGet = true

        assertTrue(storage.putBatch(mapOf(Pair(InnerTubeCookieKey, "SAPISID=fresh"))))

        val reloaded = SecureCredentialStorage(prefs, keyAccess)
        assertEquals("visitor-initial", reloaded.get(VisitorDataKey))
        assertEquals("SAPISID=fresh", reloaded.get(InnerTubeCookieKey))
    }

    @Test
    fun `migrates legacy datastore secrets and scrubs plaintext datastore keys`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val keyAccess = FakeKeyAccess()
        val storage = SecureCredentialStorage(prefs, keyAccess)
        // Simulate VisitorDataKey already present in secure storage (must not be overwritten)
        storage.putBatch(mapOf(Pair(VisitorDataKey, "secure-visitor")))

        val store = InMemoryPreferencesDataStore(
            mutablePreferencesOf(
                InnerTubeCookieKey to "SAPISID=legacy",
                VisitorDataKey to "legacy-visitor",
                DataSyncIdKey to "sync-1",
                DeeplApiKey to "deepl-key",
            ),
        )

        storage.migrateFromDataStore(store) { transform ->
            store.edit { mutable -> transform(mutable) }
            true
        }

        assertEquals("SAPISID=legacy", storage.get(InnerTubeCookieKey))
        assertEquals("secure-visitor", storage.get(VisitorDataKey))
        assertEquals("sync-1", storage.get(DataSyncIdKey))
        assertEquals("deepl-key", storage.get(DeeplApiKey))

        val scrubbed = store.data.first()
        assertNull(scrubbed[InnerTubeCookieKey])
        assertNull(scrubbed[VisitorDataKey])
        assertNull(scrubbed[DataSyncIdKey])
        assertNull(scrubbed[DeeplApiKey])
    }

    @Test
    fun `remove clearAll and allDecryptedEntries update flows accurately`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val storage = SecureCredentialStorage(prefs, FakeKeyAccess())
        val cookieFlow = storage.flow(InnerTubeCookieKey)

        storage.putBatch(
            mapOf(
                Pair(InnerTubeCookieKey, "cookie"),
                Pair(LastFMSessionKey, "session"),
            ),
        )
        assertEquals("cookie", cookieFlow.first())
        assertEquals(
            mapOf(
                Pair(InnerTubeCookieKey, "cookie"),
                Pair(LastFMSessionKey, "session"),
            ),
            storage.allDecryptedEntries(),
        )

        storage.remove(InnerTubeCookieKey)
        assertNull(cookieFlow.first())
        assertEquals(mapOf(Pair(LastFMSessionKey, "session")), storage.allDecryptedEntries())

        storage.clearAll()
        assertTrue(storage.allDecryptedEntries().isEmpty())
    }

    private class InMemoryPreferencesDataStore(
        initial: Preferences,
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(state.value).also { state.value = it }
            }
    }

    private class FakeKeyAccess : SecureKeyAccess {
        var throwInvalidatedOnNextGet = false
        private var currentKey: SecretKey = generateAesKey()

        override fun getOrCreateSecretKey(): SecretKey {
            if (throwInvalidatedOnNextGet) {
                throwInvalidatedOnNextGet = false
                throw InvalidKeyException("simulated key invalidation")
            }
            return currentKey
        }

        override fun recreateSecretKey(): SecretKey {
            currentKey = generateAesKey()
            return currentKey
        }

        private fun generateAesKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) data.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}

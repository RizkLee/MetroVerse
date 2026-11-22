/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.VisitorDataKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.InvalidKeyException
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SecureKeyAccess {
    fun getOrCreateSecretKey(): SecretKey
    fun recreateSecretKey(): SecretKey
}

private class AndroidKeyStoreAccess : SecureKeyAccess {
    override fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return generateKey()
    }

    override fun recreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "metroverse_secure_settings_aes256_gcm_v1"
    }
}

internal class SecureCredentialStorage(
    private val prefs: SharedPreferences,
    private val keyAccess: SecureKeyAccess,
) {
    private val lock = Any()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val decoder = Base64.getDecoder()
    private val encoder = Base64.getEncoder()

    @Volatile
    private var loaded = false

    fun loadAll() {
        synchronized(lock) {
            if (loaded) return
            val corruptKeys = mutableListOf<String>()
            var invalidated = false

            for (key in SECURE_CREDENTIAL_KEYS) {
                val encoded = prefs.getString(key.name, null)
                if (encoded == null) {
                    flowFor(key.name).value = null
                    continue
                }
                when (val result = decrypt(key.name, encoded)) {
                    is DecryptResult.Success -> flowFor(key.name).value = result.plaintext
                    is DecryptResult.KeyInvalidated -> {
                        invalidated = true
                        break
                    }
                    is DecryptResult.Corrupt -> {
                        corruptKeys += key.name
                        flowFor(key.name).value = null
                    }
                }
            }

            if (invalidated) {
                Timber.w("Keystore key permanently invalidated; clearing unreadable secure credentials")
                runCatching { keyAccess.recreateSecretKey() }
                prefs.edit().clear().commit()
                for (key in SECURE_CREDENTIAL_KEYS) {
                    flowFor(key.name).value = null
                }
            } else if (corruptKeys.isNotEmpty()) {
                Timber.w("Removing ${corruptKeys.size} corrupt secure credential entry(s)")
                val editor = prefs.edit()
                corruptKeys.forEach { editor.remove(it) }
                editor.commit()
            }

            loaded = true
        }
    }

    fun get(key: Preferences.Key<String>): String? {
        if (!loaded) loadAll()
        return flowFor(key.name).value
    }

    fun flow(key: Preferences.Key<String>): Flow<String?> {
        if (!loaded) loadAll()
        return flowFor(key.name)
    }

    fun putBatch(updates: Map<Preferences.Key<String>, String?>): Boolean {
        synchronized(lock) {
            if (!loaded) loadAll()
            for (key in updates.keys) {
                require(isSecureKey(key)) { "Key '${key.name}' is not registered in SecureCredentialStore" }
            }
            return try {
                tryWriteBatch(updates)
            } catch (_: KeyPermanentlyInvalidatedException) {
                rotateAndReencryptAll(updates)
            } catch (_: InvalidKeyException) {
                rotateAndReencryptAll(updates)
            } catch (e: Exception) {
                Timber.e("Failed to persist secure credentials: %s", e.javaClass.simpleName)
                false
            }
        }
    }

    private fun tryWriteBatch(updates: Map<Preferences.Key<String>, String?>): Boolean {
        val secretKey = if (updates.values.any { it != null }) {
            keyAccess.getOrCreateSecretKey()
        } else {
            null
        }
        val editor = prefs.edit()
        val applied = HashMap<String, String?>(updates.size)
        for ((key, value) in updates) {
            if (value == null) {
                editor.remove(key.name)
            } else {
                editor.putString(key.name, encrypt(requireNotNull(secretKey), key.name, value))
            }
            applied[key.name] = value
        }
        val committed = editor.commit()
        if (committed) {
            applied.forEach { (name, value) -> flowFor(name).value = value }
        }
        return committed
    }

    private fun rotateAndReencryptAll(updates: Map<Preferences.Key<String>, String?>): Boolean {
        return try {
            val newKey = keyAccess.recreateSecretKey()
            val merged = LinkedHashMap<String, String?>()
            for (key in SECURE_CREDENTIAL_KEYS) {
                val cached = flowFor(key.name).value
                if (cached != null) {
                    merged[key.name] = cached
                }
            }
            for ((key, value) in updates) {
                merged[key.name] = value
            }
            val editor = prefs.edit().clear()
            for ((name, value) in merged) {
                if (value != null) {
                    editor.putString(name, encrypt(newKey, name, value))
                }
            }
            val committed = editor.commit()
            if (committed) {
                for (key in SECURE_CREDENTIAL_KEYS) {
                    if (merged.containsKey(key.name)) {
                        flowFor(key.name).value = merged[key.name]
                    }
                }
            }
            committed
        } catch (e: Exception) {
            Timber.e("Failed to rotate secure credential key: %s", e.javaClass.simpleName)
            false
        }
    }

    fun remove(vararg keys: Preferences.Key<String>): Boolean {
        if (keys.isEmpty()) return true
        val updates = keys.associateWith<Preferences.Key<String>, String?> { null }
        return putBatch(updates)
    }

    fun clearAll(): Boolean {
        synchronized(lock) {
            val committed = prefs.edit().clear().commit()
            if (committed) {
                for (key in SECURE_CREDENTIAL_KEYS) {
                    flowFor(key.name).value = null
                }
                loaded = true
            }
            return committed
        }
    }

    fun allDecryptedEntries(): Map<Preferences.Key<String>, String> {
        if (!loaded) loadAll()
        val result = LinkedHashMap<Preferences.Key<String>, String>()
        for (key in SECURE_CREDENTIAL_KEYS) {
            val value = flowFor(key.name).value
            if (value != null) {
                result[key] = value
            }
        }
        return result
    }

    suspend fun migrateFromDataStore(
        store: DataStore<Preferences>,
        editStore: suspend (suspend (MutablePreferences) -> Unit) -> Boolean,
    ) {
        val currentPrefs = runCatching { store.data.first() }.getOrNull() ?: return
        val pendingMoves = LinkedHashMap<Preferences.Key<String>, String?>()
        val keysToScrub = ArrayList<Preferences.Key<String>>()

        synchronized(lock) {
            if (!loaded) loadAll()
            for (key in SECURE_CREDENTIAL_KEYS) {
                val legacyValue = currentPrefs[key]
                if (legacyValue != null) {
                    keysToScrub += key
                    if (flowFor(key.name).value == null) {
                        pendingMoves[key] = legacyValue
                    }
                }
            }
        }

        if (pendingMoves.isNotEmpty()) {
            if (!putBatch(pendingMoves)) {
                Timber.e("Failed to commit migrated credentials to secure storage; retaining legacy DataStore entries")
                return
            }
        }

        if (keysToScrub.isNotEmpty()) {
            editStore { mutablePrefs ->
                keysToScrub.forEach { mutablePrefs.remove(it) }
            }
        }
    }

    private fun flowFor(keyName: String): MutableStateFlow<String?> =
        flows.computeIfAbsent(keyName) { MutableStateFlow(null) }

    private fun encrypt(secretKey: SecretKey, keyName: String, plaintext: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        // Let the provider/Keystore generate its own fresh random GCM IV (required when randomized encryption is enabled).
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        check(iv != null && iv.size == GCM_IV_LENGTH) { "Unexpected GCM IV length: ${iv?.size}" }
        cipher.updateAAD(keyName.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
        return FORMAT_V1 + encoder.encodeToString(payload)
    }

    private sealed interface DecryptResult {
        data class Success(val plaintext: String) : DecryptResult
        data object KeyInvalidated : DecryptResult
        data object Corrupt : DecryptResult
    }

    private fun decrypt(keyName: String, encoded: String): DecryptResult {
        if (!encoded.startsWith(FORMAT_V1)) return DecryptResult.Corrupt
        val payload = runCatching {
            decoder.decode(encoded.removePrefix(FORMAT_V1))
        }.getOrNull() ?: return DecryptResult.Corrupt
        if (payload.size < GCM_IV_LENGTH + GCM_TAG_BYTES) return DecryptResult.Corrupt

        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        return try {
            val secretKey = keyAccess.getOrCreateSecretKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(keyName.toByteArray(Charsets.UTF_8))
            val plaintextBytes = cipher.doFinal(ciphertext)
            DecryptResult.Success(String(plaintextBytes, Charsets.UTF_8))
        } catch (_: KeyPermanentlyInvalidatedException) {
            DecryptResult.KeyInvalidated
        } catch (_: InvalidKeyException) {
            DecryptResult.KeyInvalidated
        } catch (_: AEADBadTagException) {
            DecryptResult.Corrupt
        } catch (t: Throwable) {
            Timber.w(t, "Unexpected error decrypting secure key $keyName")
            DecryptResult.Corrupt
        }
    }

    companion object {
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_V1 = "v1:"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
    }
}

val SECURE_CREDENTIAL_KEYS: List<Preferences.Key<String>> = listOf(
    InnerTubeCookieKey,
    VisitorDataKey,
    DataSyncIdKey,
    LastFMSessionKey,
    OpenRouterApiKey,
    DeeplApiKey,
)

private val SECURE_KEY_NAMES: Set<String> = SECURE_CREDENTIAL_KEYS.mapTo(HashSet()) { it.name }

fun isSecureKey(key: Preferences.Key<*>): Boolean = key.name in SECURE_KEY_NAMES

object SecureCredentialStore {
    const val PREFS_NAME = "secure_settings"

    private val migrationMutex = Mutex()
    private val initLock = Any()

    @Volatile
    private var storage: SecureCredentialStorage? = null

    @Volatile
    private var migrated = false

    private fun getOrCreateStorage(context: Context): SecureCredentialStorage {
        storage?.let { return it }
        synchronized(initLock) {
            storage?.let { return it }
            val appContext = context.applicationContext ?: context
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SecureCredentialStorage(prefs, AndroidKeyStoreAccess()).also {
                it.loadAll()
                storage = it
            }
        }
    }

    fun initialize(context: Context) {
        getOrCreateStorage(context)
    }

    suspend fun initializeAndMigrate(context: Context) {
        val appContext = context.applicationContext ?: context
        withContext(Dispatchers.IO) {
            migrationMutex.withLock {
                val store = getOrCreateStorage(appContext)
                store.migrateFromDataStore(appContext.dataStore) { transform ->
                    appContext.safeDataStoreEdit { prefs ->
                        transform(prefs)
                    }
                }
                migrated = true
            }
        }
    }

    suspend fun awaitMigrated(context: Context) {
        if (!migrated) {
            initializeAndMigrate(context)
        }
    }

    suspend fun getMigrated(context: Context, key: Preferences.Key<String>): String? {
        awaitMigrated(context)
        return get(context, key)
    }

    fun get(context: Context, key: Preferences.Key<String>): String? =
        getOrCreateStorage(context).get(key)

    fun flow(context: Context, key: Preferences.Key<String>): Flow<String?> =
        getOrCreateStorage(context).flow(key)

    suspend fun put(context: Context, key: Preferences.Key<String>, value: String): Boolean =
        withContext(Dispatchers.IO) {
            getOrCreateStorage(context).putBatch(mapOf(Pair(key, value)))
        }

    suspend fun putBatch(
        context: Context,
        updates: Map<Preferences.Key<String>, String?>,
    ): Boolean = withContext(Dispatchers.IO) {
        getOrCreateStorage(context).putBatch(updates)
    }

    suspend fun remove(context: Context, vararg keys: Preferences.Key<String>): Boolean =
        withContext(Dispatchers.IO) {
            getOrCreateStorage(context).remove(*keys)
        }

    suspend fun clearAll(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            getOrCreateStorage(context).clearAll()
        }

    fun allDecryptedEntries(context: Context): Map<Preferences.Key<String>, String> =
        getOrCreateStorage(context).allDecryptedEntries()
}

@Composable
fun rememberSecurePreference(
    key: Preferences.Key<String>,
    defaultValue: String,
): MutableState<String> {
    val context = LocalContext.current
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    val stateFlow = remember(key) { SecureCredentialStore.flow(context, key) }
    val initial = remember(key) { SecureCredentialStore.get(context, key) ?: defaultValue }
    val state = stateFlow.collectAsState(initial = initial)

    return remember(key, defaultValue) {
        object : MutableState<String> {
            override var value: String
                get() = state.value ?: defaultValue
                set(value) {
                    coroutineScope.launch(Dispatchers.IO) {
                        SecureCredentialStore.put(context, key, value)
                    }
                }

            override fun component1(): String = value
            override fun component2(): (String) -> Unit = { value = it }
        }
    }
}

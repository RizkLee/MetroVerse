/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.SharedPreferences
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientHealthTrackerTest {
    @Test
    fun `consecutive failures trigger cooldown and success restores positive health`() {
        var clockMs = 10_000L
        val scope = ClientHealthScope.from(ContentHints(), authenticated = false)
        val tracker = ClientHealthTracker(
            nowMs = { clockMs },
            accountFingerprint = { "|false" },
            networkScopeProvider = { "wifi:validated" },
        )

        tracker.recordFailure("WEB_REMIX", ClientFailureKind.PLAYABILITY, scope)
        assertEquals(1, tracker.health("WEB_REMIX", scope).consecutiveFailures)
        assertNull(tracker.health("WEB_REMIX", scope).cooldownUntilMs)
        assertFalse("WEB_REMIX" in tracker.clientsInCooldown(scope))

        tracker.recordFailure("WEB_REMIX", ClientFailureKind.PLAYABILITY, scope)
        assertEquals(2, tracker.health("WEB_REMIX", scope).consecutiveFailures)
        assertEquals(clockMs + ClientHealthTracker.DEFAULT_COOLDOWN_MS, tracker.health("WEB_REMIX", scope).cooldownUntilMs)
        assertTrue("WEB_REMIX" in tracker.clientsInCooldown(scope))
        assertTrue(tracker.scoreAdjustment("WEB_REMIX", scope) < -ClientHealthTracker.COOLDOWN_PENALTY)

        tracker.recordSuccess("WEB_REMIX", scope)
        assertEquals(0, tracker.health("WEB_REMIX", scope).consecutiveFailures)
        assertNull(tracker.health("WEB_REMIX", scope).cooldownUntilMs)
        assertFalse("WEB_REMIX" in tracker.clientsInCooldown(scope))
        assertEquals(ClientHealthTracker.SUCCESS_BONUS, tracker.scoreAdjustment("WEB_REMIX", scope))
    }

    @Test
    fun `forbidden failure puts client on extended cooldown and persists across instances`() {
        var clockMs = 50_000L
        var account = "acct-1|true"
        val prefs = InMemorySharedPreferences()
        val scope = ClientHealthScope.from(ContentHints(isExplicit = true), authenticated = true)

        val writer = ClientHealthTracker(
            preferences = prefs,
            nowMs = { clockMs },
            accountFingerprint = { account },
            networkScopeProvider = { "wifi:validated" },
            persistenceEnabled = true,
        )
        writer.recordFailure("ANDROID_VR", ClientFailureKind.MEDIA_FORBIDDEN, scope)
        assertEquals(
            clockMs + ClientHealthTracker.FORBIDDEN_COOLDOWN_MS,
            writer.health("ANDROID_VR", scope).cooldownUntilMs,
        )

        val reloaded = ClientHealthTracker(
            preferences = prefs,
            nowMs = { clockMs },
            accountFingerprint = { account },
            networkScopeProvider = { "wifi:validated" },
            persistenceEnabled = true,
        )
        assertEquals(1, reloaded.health("ANDROID_VR", scope).failures)
        assertTrue(reloaded.scoreAdjustment("ANDROID_VR", scope) < 0)

        // Switching account isolates health state cleanly
        account = "acct-2|true"
        assertEquals(0, reloaded.health("ANDROID_VR", scope).failures)
        assertEquals(0, reloaded.scoreAdjustment("ANDROID_VR", scope))
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

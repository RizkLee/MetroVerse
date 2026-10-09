/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@Suppress("DEPRECATION")
class NetworkConnectivityObserverTest {
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var connectivity: ShadowConnectivityManager
    private val observers = mutableListOf<NetworkConnectivityObserver>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity = shadowOf(connectivityManager)
        connectivity.setDefaultNetworkActive(true)
        connectivity.setActiveNetworkInfo(null)
    }

    @After
    fun tearDown() {
        observers.forEach(NetworkConnectivityObserver::unregister)
    }

    @Test
    fun `initial status is disconnected without an active network`() {
        assertFalse(createObserver().networkStatus.value)
    }

    @Test
    fun `initial status uses internet capability without requiring validation`() {
        val network = connect()
        val capabilities = checkNotNull(connectivityManager.getNetworkCapabilities(network))
        assertFalse(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertTrue(createObserver().networkStatus.value)

        setInternetCapability(network, false)
        assertFalse(createObserver().networkStatus.value)
    }

    @Test
    fun `late subscribers each receive the current status`() = runBlocking {
        val observer = createObserver()
        val network = connect()
        connectivity.networkCallbacks.single().onAvailable(network)
        val firstValues = mutableListOf<Boolean>()
        val secondValues = mutableListOf<Boolean>()
        val first = launch(Dispatchers.Unconfined) { observer.networkStatus.take(1).toList(firstValues) }
        val second = launch(Dispatchers.Unconfined) { observer.networkStatus.take(1).toList(secondValues) }
        try {
            assertEquals(listOf(true), firstValues)
            assertEquals(listOf(true), secondValues)
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun `both active subscribers receive availability capability and loss changes`() = runBlocking {
        val observer = createObserver()
        val callback = connectivity.networkCallbacks.single()
        val firstValues = mutableListOf<Boolean>()
        val secondValues = mutableListOf<Boolean>()
        val first = launch(Dispatchers.Unconfined) { observer.networkStatus.collect { firstValues += it } }
        val second = launch(Dispatchers.Unconfined) { observer.networkStatus.collect { secondValues += it } }
        try {
            assertEquals(listOf(false), firstValues)
            assertEquals(listOf(false), secondValues)

            val network = connect()
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, setInternetCapability(network, false))
            callback.onCapabilitiesChanged(network, setInternetCapability(network, true))
            connectivity.setActiveNetworkInfo(null)
            callback.onLost(network)

            val expected = listOf(false, true, false, true, false)
            assertEquals(expected, firstValues)
            assertEquals(expected, secondValues)
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    private fun createObserver() = NetworkConnectivityObserver(context).also(observers::add)

    private fun connect(): Network {
        connectivity.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true,
            ),
        )
        return checkNotNull(connectivityManager.activeNetwork).also { setInternetCapability(it, true) }
    }

    private fun setInternetCapability(network: Network, available: Boolean): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).apply {
            clearCapabilities()
            if (available) addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        connectivity.setNetworkCapabilities(network, capabilities)
        return capabilities
    }
}

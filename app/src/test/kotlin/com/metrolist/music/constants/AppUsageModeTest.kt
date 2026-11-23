/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.constants

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.ui.screens.settings.NavigationTab
import com.metrolist.music.ui.screens.settings.deserializeNavigationTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageModeTest {
    @Test
    fun resolveAppUsageMode_defaultsToMusicAndPodcasts() {
        assertEquals(AppUsageMode.MUSIC_AND_PODCASTS, resolveAppUsageMode(null))
        assertEquals(AppUsageMode.MUSIC_AND_PODCASTS, resolveAppUsageMode(""))
        assertEquals(AppUsageMode.MUSIC_AND_PODCASTS, resolveAppUsageMode("unknown"))
        assertEquals(AppUsageMode.PODCASTS_ONLY, resolveAppUsageMode("podcasts_only"))
    }

    @Test
    fun navigationTabsForUsageMode_hidesHomeAndKeepsMinimumVisibleInPodcastsOnly() {
        val sparse = "HOME:true,PODCAST:false,SEARCH:false,LIBRARY:false"
        val updated = deserializeNavigationTabs(navigationTabsForUsageMode(sparse, AppUsageMode.PODCASTS_ONLY))
        assertFalse(updated.first { it.first == NavigationTab.HOME }.second)
        assertTrue(updated.first { it.first == NavigationTab.PODCAST }.second)
        assertTrue(updated.count { it.second } >= 2)
    }

    @Test
    fun navigationTabsForUsageMode_preservesOrderAndRestoresHome() {
        val customOrder = "LIBRARY:true,PODCAST:true,HOME:true,SEARCH:false"
        val podcastsOnly = navigationTabsForUsageMode(customOrder, AppUsageMode.PODCASTS_ONLY)
        assertEquals("LIBRARY:true,PODCAST:true,HOME:false,SEARCH:false", podcastsOnly)

        val restored = navigationTabsForUsageMode(podcastsOnly, AppUsageMode.MUSIC_AND_PODCASTS)
        assertEquals("LIBRARY:true,PODCAST:true,HOME:true,SEARCH:false", restored)
    }

    @Test
    fun applyAppUsageMode_configuresPodcastsOnlyAndRestoresMusicAndPodcasts() {
        val prefs = mutablePreferencesOf()

        applyAppUsageMode(prefs, AppUsageMode.PODCASTS_ONLY)
        assertEquals(AppUsageMode.PODCASTS_ONLY.name, prefs[AppUsageModeKey])
        assertEquals(true, prefs[UsageModeOnboardingCompletedKey])
        assertEquals(NavigationTab.PODCAST.name, prefs[DefaultOpenTabKey])
        assertEquals(LibraryFilter.PODCASTS.name, prefs[ChipSortTypeKey])
        assertEquals(SearchSource.PODCAST.name, prefs[SearchSourceKey])
        assertTrue(prefs[NavigationBarTabsKey]!!.contains("HOME:false"))

        applyAppUsageMode(prefs, AppUsageMode.MUSIC_AND_PODCASTS)
        assertEquals(AppUsageMode.MUSIC_AND_PODCASTS.name, prefs[AppUsageModeKey])
        assertEquals(true, prefs[UsageModeOnboardingCompletedKey])
        assertEquals(NavigationTab.HOME.name, prefs[DefaultOpenTabKey])
        assertEquals(LibraryFilter.LIBRARY.name, prefs[ChipSortTypeKey])
        assertEquals(SearchSource.ONLINE.name, prefs[SearchSourceKey])
        assertTrue(prefs[NavigationBarTabsKey]!!.contains("HOME:true"))
    }
}

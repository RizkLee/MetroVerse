/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import com.metrolist.music.ui.screens.Screens
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationBarConfigTest {
    @Test
    fun blankPreferenceReturnsDefaultTabOrderAndAllVisible() {
        val result = deserializeNavigationTabs("")
        assertEquals(
            listOf(
                NavigationTab.HOME to true,
                NavigationTab.PODCAST to true,
                NavigationTab.SEARCH to true,
                NavigationTab.LIBRARY to true,
            ),
            result,
        )
        assertEquals(
            listOf(Screens.Home, Screens.Podcast, Screens.Search, Screens.Library),
            visibleNavigationScreens(""),
        )
    }

    @Test
    fun serializeAndDeserializePreservesCustomOrderAndHiddenTabs() {
        val custom =
            listOf(
                NavigationTab.LIBRARY to true,
                NavigationTab.HOME to true,
                NavigationTab.PODCAST to false,
                NavigationTab.SEARCH to false,
            )
        val raw = serializeNavigationTabs(custom)
        assertEquals("LIBRARY:true,HOME:true,PODCAST:false,SEARCH:false", raw)
        assertEquals(custom, deserializeNavigationTabs(raw))
        assertEquals(listOf(Screens.Library, Screens.Home), visibleNavigationScreens(raw))
    }

    @Test
    fun normalizeAppendsMissingTabsAndGuaranteesAtLeastTwoVisibleTabs() {
        val allDisabled =
            listOf(
                NavigationTab.SEARCH to false,
                NavigationTab.HOME to false,
            )
        val normalized = normalizeNavigationTabs(allDisabled)
        assertEquals(
            listOf(
                NavigationTab.SEARCH to false,
                NavigationTab.HOME to false,
                NavigationTab.PODCAST to true,
                NavigationTab.LIBRARY to true,
            ),
            normalized,
        )

        val everyTabDisabled =
            DefaultNavigationTabOrder.map { it to false }
        val normalizedAllDisabled = normalizeNavigationTabs(everyTabDisabled)
        assertEquals(true, normalizedAllDisabled[0].second)
        assertEquals(true, normalizedAllDisabled[1].second)
        assertEquals(2, normalizedAllDisabled.count { it.second })

        val onlyOneEnabled =
            listOf(
                NavigationTab.HOME to false,
                NavigationTab.PODCAST to false,
                NavigationTab.SEARCH to true,
                NavigationTab.LIBRARY to false,
            )
        val normalizedOneEnabled = normalizeNavigationTabs(onlyOneEnabled)
        assertEquals(true, normalizedOneEnabled[0].second)
        assertEquals(true, normalizedOneEnabled[2].second)
        assertEquals(2, normalizedOneEnabled.count { it.second })
    }

    @Test
    fun resolveStartNavigationScreenFallsBackToFirstVisibleTabWhenDefaultIsHidden() {
        val raw = "PODCAST:true,LIBRARY:true,HOME:false,SEARCH:false"
        assertEquals(
            Screens.Podcast,
            resolveStartNavigationScreen(raw = raw, defaultOpenTab = NavigationTab.HOME),
        )
        assertEquals(
            Screens.Library,
            resolveStartNavigationScreen(raw = raw, defaultOpenTab = NavigationTab.LIBRARY),
        )
        assertEquals(
            Screens.Search,
            resolveStartNavigationScreen(
                raw = raw,
                defaultOpenTab = NavigationTab.HOME,
                shortcutTab = NavigationTab.SEARCH,
            ),
        )
    }
}

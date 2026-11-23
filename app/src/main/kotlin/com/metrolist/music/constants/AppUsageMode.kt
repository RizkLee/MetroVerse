/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.constants

import androidx.datastore.preferences.core.MutablePreferences
import com.metrolist.music.ui.screens.settings.DefaultNavigationTabOrder
import com.metrolist.music.ui.screens.settings.MIN_VISIBLE_NAVIGATION_TABS
import com.metrolist.music.ui.screens.settings.NavigationTab
import com.metrolist.music.ui.screens.settings.deserializeNavigationTabs
import com.metrolist.music.ui.screens.settings.normalizeNavigationTabs

enum class AppUsageMode {
    MUSIC_AND_PODCASTS,
    PODCASTS_ONLY,
}

fun resolveAppUsageMode(raw: String?): AppUsageMode =
    AppUsageMode.entries.find { it.name.equals(raw?.trim(), ignoreCase = true) }
        ?: AppUsageMode.MUSIC_AND_PODCASTS

/**
 * Updates serialized bottom navigation tab configuration for [mode] while preserving the user's
 * custom tab order and keeping at least [MIN_VISIBLE_NAVIGATION_TABS] non-Home tabs enabled in
 * [AppUsageMode.PODCASTS_ONLY] so [normalizeNavigationTabs] never re-enables Home.
 */
fun navigationTabsForUsageMode(
    raw: String,
    mode: AppUsageMode,
): String {
    val parsed =
        if (raw.isBlank()) {
            DefaultNavigationTabOrder.map { it to true }
        } else {
            deserializeNavigationTabs(raw)
        }
    val updated =
        when (mode) {
            AppUsageMode.PODCASTS_ONLY -> {
                val base =
                    parsed.map { (tab, enabled) ->
                        when (tab) {
                            NavigationTab.HOME -> tab to false
                            NavigationTab.PODCAST -> tab to true
                            else -> tab to enabled
                        }
                    }
                var nonHomeEnabled = base.count { (tab, enabled) -> tab != NavigationTab.HOME && enabled }
                if (nonHomeEnabled >= MIN_VISIBLE_NAVIGATION_TABS) {
                    base
                } else {
                    base.map { (tab, enabled) ->
                        if (tab != NavigationTab.HOME && !enabled && nonHomeEnabled < MIN_VISIBLE_NAVIGATION_TABS) {
                            nonHomeEnabled++
                            tab to true
                        } else {
                            tab to enabled
                        }
                    }
                }
            }

            AppUsageMode.MUSIC_AND_PODCASTS -> {
                val withHome =
                    parsed.map { (tab, enabled) ->
                        if (tab == NavigationTab.HOME) tab to true else tab to enabled
                    }
                normalizeNavigationTabs(withHome)
            }
        }
    return updated.joinToString(",") { (tab, enabled) -> "${tab.name}:$enabled" }
}

fun applyAppUsageMode(
    settings: MutablePreferences,
    mode: AppUsageMode,
) {
    val previousMode = resolveAppUsageMode(settings[AppUsageModeKey])
    settings[AppUsageModeKey] = mode.name
    settings[UsageModeOnboardingCompletedKey] = true
    settings[NavigationBarTabsKey] = navigationTabsForUsageMode(settings[NavigationBarTabsKey] ?: "", mode)
    when (mode) {
        AppUsageMode.PODCASTS_ONLY -> {
            settings[DefaultOpenTabKey] = NavigationTab.PODCAST.name
            settings[ChipSortTypeKey] = LibraryFilter.PODCASTS.name
            settings[SearchSourceKey] = SearchSource.PODCAST.name
        }

        AppUsageMode.MUSIC_AND_PODCASTS -> {
            settings[DefaultOpenTabKey] = NavigationTab.HOME.name
            val wasPodcastsOnly = previousMode == AppUsageMode.PODCASTS_ONLY
            if (wasPodcastsOnly || settings[ChipSortTypeKey] == LibraryFilter.PODCASTS.name) {
                settings[ChipSortTypeKey] = LibraryFilter.LIBRARY.name
            }
            if (wasPodcastsOnly || settings[SearchSourceKey] == SearchSource.PODCAST.name) {
                settings[SearchSourceKey] = SearchSource.ONLINE.name
            }
        }
    }
}

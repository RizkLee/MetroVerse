/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.screens.settings

import com.metrolist.music.ui.screens.Screens

const val MIN_VISIBLE_NAVIGATION_TABS = 2

val DefaultNavigationTabOrder: List<NavigationTab> =
    listOf(
        NavigationTab.HOME,
        NavigationTab.PODCAST,
        NavigationTab.SEARCH,
        NavigationTab.LIBRARY,
    )

fun NavigationTab.toScreen(): Screens =
    when (this) {
        NavigationTab.HOME -> Screens.Home
        NavigationTab.PODCAST -> Screens.Podcast
        NavigationTab.SEARCH -> Screens.Search
        NavigationTab.LIBRARY -> Screens.Library
    }

fun serializeNavigationTabs(tabs: List<Pair<NavigationTab, Boolean>>): String {
    val normalized = normalizeNavigationTabs(tabs)
    return normalized.joinToString(",") { (tab, enabled) -> "${tab.name}:$enabled" }
}

fun deserializeNavigationTabs(raw: String): List<Pair<NavigationTab, Boolean>> {
    if (raw.isBlank()) {
        return DefaultNavigationTabOrder.map { it to true }
    }
    val parsed =
        raw.split(",").mapNotNull { token ->
            val parts = token.split(":")
            if (parts.size != 2) return@mapNotNull null
            val tab = DefaultNavigationTabOrder.find { it.name.equals(parts[0].trim(), ignoreCase = true) }
                ?: return@mapNotNull null
            val enabled = parts[1].trim().toBooleanStrictOrNull() ?: true
            tab to enabled
        }
    return normalizeNavigationTabs(parsed)
}

fun normalizeNavigationTabs(tabs: List<Pair<NavigationTab, Boolean>>): List<Pair<NavigationTab, Boolean>> {
    val seen = mutableSetOf<NavigationTab>()
    val deduplicated =
        tabs.filter { (tab, _) ->
            tab in DefaultNavigationTabOrder && seen.add(tab)
        }
    val missing =
        DefaultNavigationTabOrder
            .filter { it !in seen }
            .map { it to true }
    val combined = deduplicated + missing
    var enabledCount = combined.count { it.second }
    if (enabledCount >= MIN_VISIBLE_NAVIGATION_TABS) {
        return combined
    }
    return combined.map { (tab, enabled) ->
        if (!enabled && enabledCount < MIN_VISIBLE_NAVIGATION_TABS) {
            enabledCount++
            tab to true
        } else {
            tab to enabled
        }
    }
}

fun visibleNavigationScreens(raw: String): List<Screens> =
    deserializeNavigationTabs(raw)
        .filter { it.second }
        .map { it.first.toScreen() }

fun resolveStartNavigationScreen(
    raw: String,
    defaultOpenTab: NavigationTab,
    shortcutTab: NavigationTab? = null,
): Screens {
    if (shortcutTab != null) {
        return shortcutTab.toScreen()
    }
    val visibleTabs = deserializeNavigationTabs(raw).filter { it.second }.map { it.first }
    val effectiveTab = if (defaultOpenTab in visibleTabs) defaultOpenTab else visibleTabs.firstOrNull() ?: NavigationTab.HOME
    return effectiveTab.toScreen()
}

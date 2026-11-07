/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.screens.settings.DefaultNavigationTabOrder
import com.metrolist.music.ui.screens.settings.MIN_VISIBLE_NAVIGATION_TABS
import com.metrolist.music.ui.screens.settings.NavigationTab
import com.metrolist.music.ui.screens.settings.deserializeNavigationTabs
import com.metrolist.music.ui.screens.settings.normalizeNavigationTabs
import com.metrolist.music.ui.screens.settings.serializeNavigationTabs
import com.metrolist.music.ui.screens.settings.toScreen
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun NavigationTab.label(): String =
    when (this) {
        NavigationTab.HOME -> stringResource(R.string.home)
        NavigationTab.SEARCH -> stringResource(R.string.search)
        NavigationTab.PODCAST -> stringResource(R.string.podcast)
        NavigationTab.LIBRARY -> stringResource(R.string.filter_library)
    }

@Composable
fun NavigationBarTabsDialog(
    initialTabsRaw: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var tabs by remember(initialTabsRaw) {
        mutableStateOf(deserializeNavigationTabs(initialTabsRaw))
    }

    val enabledCount = tabs.count { it.second }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = from.index
        val toIndex = to.index
        if (fromIndex in tabs.indices && toIndex in tabs.indices) {
            tabs = tabs.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.nav_bar),
                contentDescription = null,
            )
        },
        title = {
            Text(text = stringResource(R.string.navigation_bar_tabs))
        },
        buttons = {
            TextButton(
                onClick = {
                    tabs = DefaultNavigationTabOrder.map { it to true }
                },
            ) {
                Text(stringResource(R.string.reset))
            }

            Spacer(modifier = Modifier.weight(1f))

            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }

            TextButton(
                onClick = {
                    onSave(serializeNavigationTabs(normalizeNavigationTabs(tabs)))
                    onDismiss()
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.navigation_bar_tabs_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Live interactive preview of the bottom navigation bar
            val visibleTabs = remember(tabs) { tabs.filter { it.second }.map { it.first } }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(20.dp),
                    ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    visibleTabs.forEachIndexed { index, tab ->
                        val screen = tab.toScreen()
                        val isFirst = index == 0
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isFirst) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isFirst) screen.iconIdActive else screen.iconIdInactive,
                                    ),
                                    contentDescription = null,
                                    tint = if (isFirst) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                text = tab.label(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isFirst) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isFirst) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(tabs, key = { (tab, _) -> tab.name }) { (tab, enabled) ->
                    val canToggleOff = !enabled || enabledCount > MIN_VISIBLE_NAVIGATION_TABS
                    val screen = tab.toScreen()

                    ReorderableItem(reorderableState, key = tab.name) { isDragging ->
                        val elevation by animateDpAsState(
                            targetValue = if (isDragging) 8.dp else 0.dp,
                            label = "dragElevation",
                        )
                        val scale by animateFloatAsState(
                            targetValue = if (isDragging) 1.02f else 1f,
                            label = "dragScale",
                        )
                        val containerColor by animateColorAsState(
                            targetValue = when {
                                isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                                enabled -> MaterialTheme.colorScheme.surfaceContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)
                            },
                            label = "cardColor",
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = canToggleOff) {
                                    val newEnabled = !enabled
                                    if (newEnabled || enabledCount > MIN_VISIBLE_NAVIGATION_TABS) {
                                        tabs = tabs.map { (t, e) ->
                                            if (t == tab) t to newEnabled else t to e
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = containerColor,
                            tonalElevation = elevation,
                            shadowElevation = elevation,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.drag_handle),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .padding(2.dp)
                                        .draggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                        ),
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (enabled) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (enabled) screen.iconIdActive else screen.iconIdInactive,
                                        ),
                                        contentDescription = null,
                                        tint = if (enabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Text(
                                    text = tab.label(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (enabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Switch(
                                    checked = enabled,
                                    enabled = canToggleOff,
                                    onCheckedChange = { newEnabled ->
                                        if (newEnabled || enabledCount > MIN_VISIBLE_NAVIGATION_TABS) {
                                            tabs = tabs.map { (t, e) ->
                                                if (t == tab) t to newEnabled else t to e
                                            }
                                        }
                                    },
                                    thumbContent = {
                                        Icon(
                                            painter = painterResource(
                                                id = if (enabled) R.drawable.check else R.drawable.close,
                                            ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

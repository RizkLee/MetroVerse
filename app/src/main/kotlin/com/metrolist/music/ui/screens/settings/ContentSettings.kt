/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AppLanguageKey
import com.metrolist.music.constants.BlockAiSlopArtistsKey
import com.metrolist.music.constants.ContentCountryKey
import com.metrolist.music.constants.ContentLanguageKey
import com.metrolist.music.constants.CountryCodeToName
import com.metrolist.music.constants.EnableBetterLyricsKey
import com.metrolist.music.constants.EnableKugouKey
import com.metrolist.music.constants.EnableLrcLibKey
import com.metrolist.music.constants.EnablePaxsenixKey
import com.metrolist.music.constants.EnableLyricsPlus
import com.metrolist.music.constants.ExplicitOnlyKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideNonMusicalVideosKey
import com.metrolist.music.constants.HidePodcastsKey
import com.metrolist.music.constants.HideShortSongsKey
import com.metrolist.music.constants.LoadLyricsInBackgroundKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.LanguageCodeToName
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.constants.MinimumSongDurationSecondsKey
import com.metrolist.music.constants.ProxyEnabledKey
import com.metrolist.music.constants.ProxyPasswordKey
import com.metrolist.music.constants.ProxyTypeKey
import com.metrolist.music.constants.ProxyUrlKey
import com.metrolist.music.constants.ProxyUsernameKey
import com.metrolist.music.constants.QuickPicks
import com.metrolist.music.constants.QuickPicksKey
import com.metrolist.music.constants.RandomizeHomeOrderKey
import com.metrolist.music.constants.SYSTEM_DEFAULT
import com.metrolist.music.constants.ShowArtistDescriptionKey
import com.metrolist.music.constants.ShowMostStatsPlaylistsKey
import com.metrolist.music.constants.ShowArtistSubscriberCountKey
import com.metrolist.music.constants.ShowMonthlyListenersKey
import com.metrolist.music.constants.ShowWrappedCardKey
import com.metrolist.music.constants.TopSize
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.DraggableLyricsProviderItem
import com.metrolist.music.ui.component.DraggableLyricsProviderList
import com.metrolist.music.lyrics.LyricsProviderRegistry
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.AiSlopBlocklist
import com.metrolist.music.utils.ContentFilterSnapshot
import com.metrolist.music.utils.rememberContentFilter
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Proxy
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentSettings(
    navController: NavController
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    // Used only before Android 13
    val (appLanguage, onAppLanguageChange) = rememberPreference(key = AppLanguageKey, defaultValue = SYSTEM_DEFAULT)

    val (contentLanguage, onContentLanguageChange) = rememberPreference(key = ContentLanguageKey, defaultValue = "system")
    val (contentCountry, onContentCountryChange) = rememberPreference(key = ContentCountryKey, defaultValue = "system")
    val contentFilter = rememberContentFilter()
    val (_, onBlockAiSlopArtistsChange) = rememberPreference(key = BlockAiSlopArtistsKey, defaultValue = false)
    val (_, onHideVideoSongsChange) = rememberPreference(key = HideVideoSongsKey, defaultValue = false)
    val (_, onHideNonMusicalVideosChange) = rememberPreference(key = HideNonMusicalVideosKey, defaultValue = false)
    val (_, onMinimumSongDurationChange) = rememberPreference(
        key = MinimumSongDurationSecondsKey,
        defaultValue = ContentFilterSnapshot.DEFAULT_MINIMUM_SONG_DURATION_SECONDS,
    )
    val (_, onHidePodcastsChange) = rememberPreference(key = HidePodcastsKey, defaultValue = false)
    var sliderDurationSeconds by remember {
        mutableFloatStateOf(contentFilter.effectiveMinimumDurationSeconds.toFloat())
    }
    LaunchedEffect(contentFilter.effectiveMinimumDurationSeconds) {
        sliderDurationSeconds = contentFilter.effectiveMinimumDurationSeconds.toFloat()
    }
    val hideExplicit = contentFilter.hideExplicit
    val explicitOnly = contentFilter.explicitOnly
    val blockAiSlopArtists = contentFilter.blockAiSlopArtists
    val hideVideoSongs = contentFilter.hideVideoSongs
    val hideNonMusicalVideos = contentFilter.hideNonMusicalVideos
    val hideShortSongs = contentFilter.hideShortSongs
    val hidePodcasts = contentFilter.hidePodcasts
    val blocklistDescription = stringResource(R.string.block_ai_slop_artists_desc)
    val primaryColor = MaterialTheme.colorScheme.primary
    val blocklistLink = remember(blocklistDescription, primaryColor) {
        buildAnnotatedString {
            append(blocklistDescription)
            val sourceName = AiSlopBlocklist.CONTRIBUTION_URL.substringAfterLast('/')
            val sourceIndex = blocklistDescription.indexOf(sourceName)
            val start = if (sourceIndex >= 0) sourceIndex else length.also { append(" ") } + 1
            if (sourceIndex < 0) append(AiSlopBlocklist.CONTRIBUTION_URL)
            addLink(
                LinkAnnotation.Url(
                    AiSlopBlocklist.CONTRIBUTION_URL,
                    TextLinkStyles(
                        SpanStyle(
                            color = primaryColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
                start,
                if (sourceIndex >= 0) start + sourceName.length else length,
            )
        }
    }
    val (showArtistDescription, onShowArtistDescriptionChange) = rememberPreference(key = ShowArtistDescriptionKey, defaultValue = true)
    val (showArtistSubscriberCount, onShowArtistSubscriberCountChange) = rememberPreference(key = ShowArtistSubscriberCountKey, defaultValue = true)
    val (showMonthlyListeners, onShowMonthlyListenersChange) = rememberPreference(key = ShowMonthlyListenersKey, defaultValue = true)
    val (proxyEnabled, onProxyEnabledChange) = rememberPreference(key = ProxyEnabledKey, defaultValue = false)
    val (proxyType, onProxyTypeChange) = rememberEnumPreference(key = ProxyTypeKey, defaultValue = Proxy.Type.HTTP)
    val (proxyUrl, onProxyUrlChange) = rememberPreference(key = ProxyUrlKey, defaultValue = "host:port")
    val (proxyUsername, onProxyUsernameChange) = rememberPreference(key = ProxyUsernameKey, defaultValue = "username")
    val (proxyPassword, onProxyPasswordChange) = rememberPreference(key = ProxyPasswordKey, defaultValue = "password")
    val (enableKugou, onEnableKugouChange) = rememberPreference(key = EnableKugouKey, defaultValue = true)
    val (enableLrclib, onEnableLrclibChange) = rememberPreference(key = EnableLrcLibKey, defaultValue = true)
    val (enableBetterLyrics, onEnableBetterLyricsChange) = rememberPreference(key = EnableBetterLyricsKey, defaultValue = true)
    val (enablePaxsenix, onEnablePaxsenixChange) = rememberPreference(key = EnablePaxsenixKey, defaultValue = true)
    val (enableLyricsPlus, onEnableLyricsPlusChange) = rememberPreference(key = EnableLyricsPlus, defaultValue = true)
    val (loadLyricsInBackground, onLoadLyricsInBackgroundChange) = rememberPreference(
        key = LoadLyricsInBackgroundKey,
        defaultValue = true,
    )
    val (lyricsProviderOrder, onLyricsProviderOrderChange) = rememberPreference(
        key = LyricsProviderOrderKey,
        defaultValue = LyricsProviderRegistry.serializeProviderOrder(LyricsProviderRegistry.getDefaultProviderOrder())
    )
    val (lengthTop, onLengthTopChange) = rememberPreference(key = TopSize, defaultValue = "50")
    val (quickPicks, onQuickPicksChange) = rememberEnumPreference(key = QuickPicksKey, defaultValue = QuickPicks.QUICK_PICKS)
    val (showWrappedCard, onShowWrappedCardChange) = rememberPreference(key = ShowWrappedCardKey, defaultValue = false)
    val (showMostStatsPlaylists, onShowMostStatsPlaylistsChange) =
        rememberPreference(key = ShowMostStatsPlaylistsKey, defaultValue = true)
    val (randomizeHomeOrder, onRandomizeHomeOrderChange) = rememberPreference(
        RandomizeHomeOrderKey,
        defaultValue = true
    )

    LaunchedEffect(showMostStatsPlaylists) {
        if (!showMostStatsPlaylists) {
            database.withTransaction {
                clearPlaylist(com.metrolist.music.db.entities.PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID)
                clearPlaylist(com.metrolist.music.db.entities.PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID)
                delete(
                    com.metrolist.music.db.entities.PlaylistEntity(
                        id = com.metrolist.music.db.entities.PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID,
                        name = "",
                    ),
                )
                delete(
                    com.metrolist.music.db.entities.PlaylistEntity(
                        id = com.metrolist.music.db.entities.PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID,
                        name = "",
                    ),
                )
            }
        }
    }

    val providerDisplayNames =
        mapOf(
            "BetterLyrics" to "Better Lyrics",
            "Paxsenix" to "Paxsenix",
            "LrcLib" to "LrcLib",
            "KuGou" to "KuGou",
            "LyricsPlus" to "LyricsPlus",
            "YouTubeSubtitle" to "YouTube Subtitles",
            "YouTube" to "YouTube",
        )

    var showProxyConfigurationDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showProxyConfigurationDialog) {
        var expandedDropdown by remember { mutableStateOf(false) }

        var tempProxyUrl by rememberSaveable { mutableStateOf(proxyUrl) }
        var tempProxyUsername by rememberSaveable { mutableStateOf(proxyUsername) }
        var tempProxyPassword by rememberSaveable { mutableStateOf(proxyPassword) }
        var authEnabled by rememberSaveable { mutableStateOf(proxyUsername.isNotBlank() || proxyPassword.isNotBlank()) }

        AlertDialog(
            onDismissRequest = { showProxyConfigurationDialog = false },
            title = {
                Text(stringResource(R.string.config_proxy))
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = expandedDropdown,
                        onExpandedChange = { expandedDropdown = !expandedDropdown },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = proxyType.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.proxy_type)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown)
                            },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS).forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.name) },
                                    onClick = {
                                        onProxyTypeChange(type)
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = tempProxyUrl,
                        onValueChange = { tempProxyUrl = it },
                        label = { Text(stringResource(R.string.proxy_url)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.enable_authentication))
                        Switch(
                            checked = authEnabled,
                            onCheckedChange = {
                                authEnabled = it
                                if (!it) {
                                    tempProxyUsername = ""
                                    tempProxyPassword = ""
                                }
                            }
                        )
                    }

                    AnimatedVisibility(visible = authEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tempProxyUsername,
                                onValueChange = { tempProxyUsername = it },
                                label = { Text(stringResource(R.string.proxy_username)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = tempProxyPassword,
                                onValueChange = { tempProxyPassword = it },
                                label = { Text(stringResource(R.string.proxy_password)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onProxyUrlChange(tempProxyUrl)
                        onProxyUsernameChange(if (authEnabled) tempProxyUsername else "")
                        onProxyPasswordChange(if (authEnabled) tempProxyPassword else "")
                        showProxyConfigurationDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showProxyConfigurationDialog = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    var showContentLanguageDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showContentLanguageDialog) {
        EnumDialog(
            onDismiss = { showContentLanguageDialog = false },
            onSelect = {
                onContentLanguageChange(it)
                showContentLanguageDialog = false
            },
            title = stringResource(R.string.content_language),
            current = contentLanguage,
            values = (listOf(SYSTEM_DEFAULT) + LanguageCodeToName.keys.toList()),
            valueText = {
                LanguageCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
            }
        )
    }

    var showContentCountryDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showContentCountryDialog) {
        EnumDialog(
            onDismiss = { showContentCountryDialog = false },
            onSelect = {
                onContentCountryChange(it)
                showContentCountryDialog = false
            },
            title = stringResource(R.string.content_country),
            current = contentCountry,
            values = (listOf(SYSTEM_DEFAULT) + CountryCodeToName.keys.toList()),
            valueText = {
                CountryCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
            }
        )
    }

    var showAppLanguageDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showAppLanguageDialog) {
        EnumDialog(
            onDismiss = { showAppLanguageDialog = false },
            onSelect = {
                onAppLanguageChange(it)
                showAppLanguageDialog = false
            },
            title = stringResource(R.string.app_language),
            current = appLanguage,
            values = (listOf(SYSTEM_DEFAULT) + LanguageCodeToName.keys.toList()),
            valueText = {
                LanguageCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
            }
        )
    }

    var showProviderSelectionDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showProviderSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showProviderSelectionDialog = false },
            title = { Text(stringResource(R.string.lyrics_provider_selection)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.enable_lrclib))
                            Text(
                                text = stringResource(R.string.enable_lrclib_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enableLrclib,
                            onCheckedChange = onEnableLrclibChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enableLrclib) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.enable_kugou))
                            Text(
                                text = stringResource(R.string.enable_kugou_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enableKugou,
                            onCheckedChange = onEnableKugouChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enableKugou) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.enable_better_lyrics))
                            Text(
                                text = stringResource(R.string.enable_better_lyrics_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enableBetterLyrics,
                            onCheckedChange = onEnableBetterLyricsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enableBetterLyrics) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.enable_paxsenix))
                            Text(
                                text = stringResource(R.string.enable_paxsenix_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enablePaxsenix,
                            onCheckedChange = onEnablePaxsenixChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enablePaxsenix) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.enable_lyricsplus))
                            Text(
                                text = stringResource(R.string.enable_lyricsplus_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enableLyricsPlus,
                            onCheckedChange = onEnableLyricsPlusChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enableLyricsPlus) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                    Column(modifier = Modifier.padding(2.dp)) {
                        Text(
                            text = stringResource(R.string.youtube_music_lyrics_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showProviderSelectionDialog = false }
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    var showQuickPicksDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showQuickPicksDialog) {
        EnumDialog(
            onDismiss = { showQuickPicksDialog = false },
            onSelect = {
                onQuickPicksChange(it)
                showQuickPicksDialog = false
            },
            title = stringResource(R.string.set_quick_picks),
            current = quickPicks,
            values = QuickPicks.values().toList(),
            valueText = {
                when (it) {
                    QuickPicks.QUICK_PICKS -> stringResource(R.string.quick_picks)
                    QuickPicks.LAST_LISTEN -> stringResource(R.string.last_song_listened)
                }
            }
        )
    }

    var showTopLengthDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showTopLengthDialog) {
        var tempLength by rememberSaveable { mutableFloatStateOf(lengthTop.toFloat()) }

        AlertDialog(
            onDismissRequest = { showTopLengthDialog = false },
            title = { Text(stringResource(R.string.top_length)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(tempLength.toInt().toString())
                    Slider(
                        value = tempLength,
                        onValueChange = { tempLength = it },
                        valueRange = 1f..100f,
                        steps = 98
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onLengthTopChange(tempLength.toInt().toString())
                        showTopLengthDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        )
    }

    var showProviderPriorityDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showProviderPriorityDialog) {
        val currentOrder = LyricsProviderRegistry.deserializeProviderOrder(lyricsProviderOrder)
        val defaultOrder = LyricsProviderRegistry.getDefaultProviderOrder()
        val normalizedOrder = currentOrder.filter { it in defaultOrder } +
            defaultOrder.filter { it !in currentOrder }

        val enabledProviders = setOf(
            "LrcLib".takeIf { enableLrclib },
            "KuGou".takeIf { enableKugou },
            "BetterLyrics".takeIf { enableBetterLyrics },
            "Paxsenix".takeIf { enablePaxsenix },
            "LyricsPlus".takeIf { enableLyricsPlus },
        ).filterNotNull().toSet()
        val lyricsIcon = painterResource(R.drawable.lyrics)
        val draggableItems = remember { mutableStateListOf<DraggableLyricsProviderItem>() }

        LaunchedEffect(normalizedOrder, enableLrclib, enableKugou, enableBetterLyrics, enablePaxsenix, enableLyricsPlus) {
            val orderedEnabledProviders = normalizedOrder.filter { it in enabledProviders }
            draggableItems.clear()
            draggableItems.addAll(
                orderedEnabledProviders.mapNotNull { providerName ->
                    LyricsProviderRegistry.getProviderByName(providerName) ?: return@mapNotNull null
                    DraggableLyricsProviderItem(
                        id = providerName,
                        name = providerDisplayNames[providerName] ?: providerName,
                        icon = lyricsIcon,
                    )
                }
            )
        }

        AlertDialog(
            onDismissRequest = { showProviderPriorityDialog = false },
            title = { Text(stringResource(R.string.lyrics_provider_priority)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    Text(
                        stringResource(R.string.lyrics_provider_priority_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DraggableLyricsProviderList(
                        items = draggableItems,
                        onItemsReordered = { reorderedItems ->
                            val enabledOrder = reorderedItems.map { it.id }
                            val disabledOrder = normalizedOrder.filter { it !in enabledProviders }
                            onLyricsProviderOrderChange(
                                LyricsProviderRegistry.serializeProviderOrder(enabledOrder + disabledOrder)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showProviderPriorityDialog = false }
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.general),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.language),
                    title = { Text(stringResource(R.string.content_language)) },
                    description = {
                        Text(
                            LanguageCodeToName.getOrElse(contentLanguage) { stringResource(R.string.system_default) }
                        )
                    },
                    onClick = { showContentLanguageDialog = true }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.location_on),
                    title = { Text(stringResource(R.string.content_country)) },
                    description = {
                        Text(
                            CountryCodeToName.getOrElse(contentCountry) { stringResource(R.string.system_default) }
                        )
                    },
                    onClick = { showContentCountryDialog = true }
                ),
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.content_filters_filtering),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.explicit),
                    title = { Text(stringResource(R.string.hide_explicit)) },
                    description = { Text(stringResource(R.string.hide_explicit_desc)) },
                    enabled = !explicitOnly,
                    trailingContent = {
                        Switch(
                            checked = hideExplicit,
                            enabled = !explicitOnly,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    context.safeDataStoreEdit { prefs ->
                                        prefs[HideExplicitKey] = enabled
                                        if (enabled) prefs[ExplicitOnlyKey] = false
                                    }
                                }
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (hideExplicit) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        if (!explicitOnly) {
                            val enabled = !hideExplicit
                            coroutineScope.launch {
                                context.safeDataStoreEdit { prefs ->
                                    prefs[HideExplicitKey] = enabled
                                    if (enabled) prefs[ExplicitOnlyKey] = false
                                }
                            }
                        }
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.explicit),
                    title = { Text(stringResource(R.string.explicit_only)) },
                    description = { Text(stringResource(R.string.explicit_only_desc)) },
                    enabled = !hideExplicit,
                    trailingContent = {
                        Switch(
                            checked = explicitOnly,
                            enabled = !hideExplicit,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    context.safeDataStoreEdit { prefs ->
                                        prefs[ExplicitOnlyKey] = enabled
                                        if (enabled) prefs[HideExplicitKey] = false
                                    }
                                }
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (explicitOnly) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        if (!hideExplicit) {
                            val enabled = !explicitOnly
                            coroutineScope.launch {
                                context.safeDataStoreEdit { prefs ->
                                    prefs[ExplicitOnlyKey] = enabled
                                    if (enabled) prefs[HideExplicitKey] = false
                                }
                            }
                        }
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.security),
                    title = { Text(stringResource(R.string.block_ai_slop_artists)) },
                    description = { Text(text = blocklistLink) },
                    trailingContent = {
                        Switch(
                            checked = blockAiSlopArtists,
                            onCheckedChange = { enabled ->
                                onBlockAiSlopArtistsChange(enabled)
                                if (enabled) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        AiSlopBlocklist.refresh(context, force = true)
                                    }
                                }
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (blockAiSlopArtists) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        val enabled = !blockAiSlopArtists
                        onBlockAiSlopArtistsChange(enabled)
                        if (enabled) {
                            coroutineScope.launch(Dispatchers.IO) {
                                AiSlopBlocklist.refresh(context, force = true)
                            }
                        }
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.slow_motion_video),
                    title = { Text(stringResource(R.string.hide_video_songs)) },
                    description = { Text(stringResource(R.string.hide_video_songs_desc)) },
                    trailingContent = {
                        Switch(
                            checked = hideVideoSongs,
                            onCheckedChange = onHideVideoSongsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (hideVideoSongs) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onHideVideoSongsChange(!hideVideoSongs) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.hide_image),
                    title = { Text(stringResource(R.string.hide_non_musical_videos)) },
                    description = { Text(stringResource(R.string.hide_non_musical_videos_desc)) },
                    trailingContent = {
                        Switch(
                            checked = hideNonMusicalVideos,
                            onCheckedChange = onHideNonMusicalVideosChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (hideNonMusicalVideos) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onHideNonMusicalVideosChange(!hideNonMusicalVideos) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.timer),
                    title = { Text(stringResource(R.string.hide_short_songs)) },
                    description = { Text(stringResource(R.string.hide_short_songs_desc)) },
                    trailingContent = {
                        Switch(
                            checked = hideShortSongs,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    context.safeDataStoreEdit { prefs ->
                                        prefs[HideShortSongsKey] = enabled
                                        prefs.remove(HideYoutubeShortsKey)
                                    }
                                }
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (hideShortSongs) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        val enabled = !hideShortSongs
                        coroutineScope.launch {
                            context.safeDataStoreEdit { prefs ->
                                prefs[HideShortSongsKey] = enabled
                                prefs.remove(HideYoutubeShortsKey)
                            }
                        }
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.tune),
                    title = { Text(stringResource(R.string.minimum_song_duration)) },
                    enabled = hideShortSongs,
                    description = {
                        val displaySeconds = sliderDurationSeconds.roundToInt()
                        Column {
                            Text(
                                text =
                                    if (hideShortSongs) {
                                        stringResource(R.string.minimum_song_duration_desc, displaySeconds)
                                    } else {
                                        stringResource(
                                            R.string.settings_requires_enabled,
                                            stringResource(R.string.hide_short_songs),
                                        )
                                    },
                            )
                            Slider(
                                value = sliderDurationSeconds,
                                onValueChange = { value ->
                                    sliderDurationSeconds =
                                        value.roundToInt()
                                            .coerceIn(
                                                ContentFilterSnapshot.MIN_SONG_DURATION_SECONDS,
                                                ContentFilterSnapshot.MAX_SONG_DURATION_SECONDS,
                                            ).toFloat()
                                },
                                onValueChangeFinished = {
                                    onMinimumSongDurationChange(sliderDurationSeconds.roundToInt())
                                },
                                valueRange =
                                    ContentFilterSnapshot.MIN_SONG_DURATION_SECONDS.toFloat()..
                                        ContentFilterSnapshot.MAX_SONG_DURATION_SECONDS.toFloat(),
                                steps = 10,
                                enabled = hideShortSongs,
                            )
                        }
                    },
                    trailingContent = {
                        Text(
                            text = stringResource(R.string.settings_seconds_value, sliderDurationSeconds.roundToInt()),
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (hideShortSongs) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                        )
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.podcast),
                    title = { Text(stringResource(R.string.hide_podcasts)) },
                    description = { Text(stringResource(R.string.hide_podcasts_desc)) },
                    trailingContent = {
                        Switch(
                            checked = hidePodcasts,
                            onCheckedChange = onHidePodcastsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (hidePodcasts) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onHidePodcastsChange(!hidePodcasts) }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.artist_page_settings),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.info),
                    title = { Text(stringResource(R.string.show_artist_description)) },
                    trailingContent = {
                        Switch(
                            checked = showArtistDescription,
                            onCheckedChange = onShowArtistDescriptionChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (showArtistDescription) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onShowArtistDescriptionChange(!showArtistDescription) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.person),
                    title = { Text(stringResource(R.string.show_artist_subscriber_count)) },
                    trailingContent = {
                        Switch(
                            checked = showArtistSubscriberCount,
                            onCheckedChange = onShowArtistSubscriberCountChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (showArtistSubscriberCount) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onShowArtistSubscriberCountChange(!showArtistSubscriberCount) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.person),
                    title = { Text(stringResource(R.string.show_artist_monthly_listeners)) },
                    trailingContent = {
                        Switch(
                            checked = showMonthlyListeners,
                            onCheckedChange = onShowMonthlyListenersChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (showMonthlyListeners) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onShowMonthlyListenersChange(!showMonthlyListeners) }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.app_language),
            items = listOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.language),
                        title = { Text(stringResource(R.string.app_language)) },
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_LOCALE_SETTINGS,
                                    "package:${context.packageName}".toUri()
                                )
                            )
                        }
                    )
                } else {
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.language),
                        title = { Text(stringResource(R.string.app_language)) },
                        description = {
                            Text(
                                LanguageCodeToName.getOrElse(appLanguage) { stringResource(R.string.system_default) }
                            )
                        },
                        onClick = { showAppLanguageDialog = true }
                    )
                }
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.proxy),
            items = buildList {
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.wifi_proxy),
                        title = { Text(stringResource(R.string.enable_proxy)) },
                        trailingContent = {
                            Switch(
                                checked = proxyEnabled,
                                onCheckedChange = onProxyEnabledChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (proxyEnabled) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onProxyEnabledChange(!proxyEnabled) }
                    )
                )
                if (proxyEnabled) {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.settings),
                            title = { Text(stringResource(R.string.config_proxy)) },
                            onClick = { showProxyConfigurationDialog = true }
                        )
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.lyrics_background_loading)) },
                    description = { Text(stringResource(R.string.lyrics_background_loading_desc)) },
                    trailingContent = {
                        Switch(
                            checked = loadLyricsInBackground,
                            onCheckedChange = onLoadLyricsInBackgroundChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (loadLyricsInBackground) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { onLoadLyricsInBackgroundChange(!loadLyricsInBackground) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.lyrics_provider_selection)) },
                    description = { Text(stringResource(R.string.lyrics_provider_selection_desc)) },
                    onClick = { showProviderSelectionDialog = true }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.lyrics_provider_priority)) },
                    description = { Text(stringResource(R.string.lyrics_provider_priority_desc)) },
                    onClick = { showProviderPriorityDialog = true }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.language_korean_latin),
                    title = { Text(stringResource(R.string.lyrics_romanization)) },
                    onClick = { navController.navigate("settings/content/romanization") }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = "Wrapped",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.stats),
                    title = { Text(stringResource(R.string.show_most_stats_playlists)) },
                    description = { Text(stringResource(R.string.show_most_stats_playlists_desc)) },
                    trailingContent = {
                        Switch(
                            checked = showMostStatsPlaylists,
                            onCheckedChange = onShowMostStatsPlaylistsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (showMostStatsPlaylists) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onShowMostStatsPlaylistsChange(!showMostStatsPlaylists) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.trending_up),
                    title = { Text(stringResource(R.string.show_wrapped_card)) },
                    trailingContent = {
                        Switch(
                            checked = showWrappedCard,
                            onCheckedChange = onShowWrappedCardChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (showWrappedCard) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onShowWrappedCardChange(!showWrappedCard) }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.misc),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.shuffle),
                    title = { Text(stringResource(R.string.randomize_home_order)) },
                    description = { Text(stringResource(R.string.randomize_home_order_desc)) },
                    trailingContent = {
                        Switch(
                            checked = randomizeHomeOrder,
                            onCheckedChange = onRandomizeHomeOrderChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (randomizeHomeOrder) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onRandomizeHomeOrderChange(!randomizeHomeOrder) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.trending_up),
                    title = { Text(stringResource(R.string.top_length)) },
                    description = { Text(lengthTop) },
                    onClick = { showTopLengthDialog = true }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.home_outlined),
                    title = { Text(stringResource(R.string.set_quick_picks)) },
                    description = {
                        Text(
                            when (quickPicks) {
                                QuickPicks.QUICK_PICKS -> stringResource(R.string.quick_picks)
                                QuickPicks.LAST_LISTEN -> stringResource(R.string.last_song_listened)
                            }
                        )
                    },
                    onClick = { showQuickPicksDialog = true }
                )
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.content)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}

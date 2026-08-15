package com.metrolist.music.ui.screens.podcast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.podcast.supportedPodcastRegions
import java.util.Locale

@Composable
fun PodcastRegionSelector(
    selectedCode: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val displayLocale = LocalConfiguration.current.locales[0]
    val selectedName = remember(selectedCode, displayLocale) {
        Locale.Builder().setRegion(selectedCode).build().getDisplayCountry(displayLocale).ifBlank { selectedCode }
    }

    Box(modifier = modifier) {
        if (compact) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.language),
                    contentDescription = stringResource(R.string.podcast_region_current, selectedName),
                )
            }
        } else {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.language),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.podcast_region_current, "$selectedName ($selectedCode)"))
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            supportedPodcastRegions.forEach { region ->
                val countryName = remember(region.code, displayLocale) {
                    Locale.Builder().setRegion(region.code).build().getDisplayCountry(displayLocale).ifBlank { region.code }
                }
                DropdownMenuItem(
                    text = { Text("$countryName (${region.code})") },
                    onClick = {
                        expanded = false
                        onSelected(region.code)
                    },
                    leadingIcon = if (region.code == selectedCode) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.check),
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

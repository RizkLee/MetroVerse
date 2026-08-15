package com.metrolist.music.ui.screens.podcast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = if (compact) Modifier else Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.language),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(selectedCode)
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

/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.podcast.transcript.AsrLanguageSelection
import java.util.Locale

@Composable
internal fun asrLanguageName(code: String?): String {
    val locale = LocalConfiguration.current.locales[0]
    return code?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale).ifBlank { it } }
        ?: stringResource(R.string.podcast_subtitle_loading)
}

/** The language target stays available when recognition is unavailable for the RSS language. */
@Composable
internal fun PodcastAsrActionButton(
    selection: AsrLanguageSelection?,
    modelReady: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onSelectLanguage: () -> Unit,
) {
    val supported = selection?.isSupported == true
    val loaded = selection?.code != null
    val foreground = if (supported) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = if (supported) onAction else onSelectLanguage,
        enabled = loaded,
        shape = CircleShape,
        color = if (supported) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = foreground,
    ) {
        Row(
            modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(if (modelReady) R.drawable.mic else R.drawable.download), null, Modifier.size(20.dp))
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier.widthIn(max = 128.dp).heightIn(min = 48.dp)
                    .clickable(enabled = loaded, role = Role.Button, onClick = onSelectLanguage),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    asrLanguageName(selection?.code),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(bottom = 3.dp).drawBehind {
                        drawLine(foreground, Offset(0f, size.height), Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
                    },
                )
                Icon(painterResource(R.drawable.expand_more), stringResource(R.string.podcast_asr_language), Modifier.size(24.dp))
            }
        }
    }
}

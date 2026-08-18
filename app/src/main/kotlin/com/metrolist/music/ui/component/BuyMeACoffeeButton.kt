/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/rizklee"
private const val BUY_ME_A_COFFEE_ASPECT_RATIO = 545f / 153f

@Composable
fun BuyMeACoffeeButton(
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            onClick = { uriHandler.openUri(BUY_ME_A_COFFEE_URL) },
            color = Color.Transparent,
            shape = RoundedCornerShape(13.dp),
            modifier =
                Modifier
                    .widthIn(max = 185.dp)
                    .aspectRatio(BUY_ME_A_COFFEE_ASPECT_RATIO),
        ) {
            Image(
                painter = painterResource(R.drawable.bmc_button),
                contentDescription = stringResource(R.string.buy_mo_a_coffee),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

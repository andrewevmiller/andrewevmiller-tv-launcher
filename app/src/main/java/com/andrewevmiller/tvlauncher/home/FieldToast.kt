package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text
import androidx.compose.ui.unit.dp
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes

/** A short mono message in a hairline-bordered panel, the design language's stand-in for an Android toast. */
@Composable
fun FieldToast(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    val colors = Field.colors
    Box(
        modifier
            .background(colors.panel, FieldShapes.tile)
            .border(FieldDimens.hairline, colors.line, FieldShapes.tile)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(message, style = Field.type.caption, color = colors.ink)
    }
}

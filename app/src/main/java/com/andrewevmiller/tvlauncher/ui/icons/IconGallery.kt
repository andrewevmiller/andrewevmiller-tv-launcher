package com.andrewevmiller.tvlauncher.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme

/** Preview only: every [FieldIcons] glyph at 24dp and 48dp on the dark theme. Not part of the app UI. */
@Preview(device = "id:tv_1080p")
@Composable
private fun IconGalleryPreview() {
    FieldTheme {
        IconGallery()
    }
}

@Composable
private fun IconGallery() {
    val colors = Field.colors
    val icons: List<Pair<String, ImageVector>> = listOf(
        "Play" to FieldIcons.Play,
        "Note" to FieldIcons.Note,
        "Pad" to FieldIcons.Pad,
        "Tool" to FieldIcons.Tool,
        "Wave" to FieldIcons.Wave,
        "Port" to FieldIcons.Port,
        "Gear" to FieldIcons.Gear,
        "Wifi" to FieldIcons.Wifi,
        "Search" to FieldIcons.Search,
        "Folder" to FieldIcons.Folder,
        "Points" to FieldIcons.Points,
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = FieldDimens.safeHorizontal, vertical = FieldDimens.safeVertical),
    ) {
        Text("LINE ICONS", style = Field.type.label, color = colors.muted)
        Spacer(Modifier.height(16.dp))
        IconSection(icons, size = 24.dp)
        Spacer(Modifier.height(24.dp))
        IconSection(icons, size = 48.dp)
    }
}

@Composable
private fun IconSection(icons: List<Pair<String, ImageVector>>, size: Dp) {
    val colors = Field.colors
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(FieldDimens.hairline)
                .background(colors.line),
        )
        Spacer(Modifier.height(12.dp))
        icons.chunked(6).forEach { rowIcons ->
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                rowIcons.forEach { (label, icon) ->
                    Column {
                        FieldIcon(icon, size = size)
                        Spacer(Modifier.height(8.dp))
                        Text(label, style = Field.type.caption, color = colors.muted)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

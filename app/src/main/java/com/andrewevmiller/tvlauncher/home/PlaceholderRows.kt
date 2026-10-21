package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.home.data.PreviewApps
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile

/**
 * Temporary rows so the first deploy shows the frame, fonts, texture and focus style.
 * Replaced in wave 3 by the real rows from home/apps and ui/components.
 */
fun placeholderRows(onLaunch: (LauncherApp) -> Unit): List<HomeRow> = listOf(
    HomeRow("favorites", "Favorites") { PlaceholderTiles(PreviewApps, onLaunch) },
    HomeRow("tilted", "Gallery tilt test") { PlaceholderTiles(PreviewApps.reversed(), onLaunch, tilt = true) },
)

@Composable
private fun PlaceholderTiles(apps: List<LauncherApp>, onLaunch: (LauncherApp) -> Unit, tilt: Boolean = false) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap),
        // Room for the focused tile's scale-up so it isn't clipped.
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
    ) {
        items(apps.size) { i ->
            val app = apps[i]
            Box(
                Modifier
                    .size(FieldDimens.bannerWidth, FieldDimens.bannerHeight)
                    .fieldTile(
                        onClick = { onLaunch(app) },
                        restTilt = if (tilt) (if (i % 2 == 0) -0.8f else 0.8f) else 0f,
                    )
                    .background(Field.colors.bgSoft, FieldShapes.tile)
                    .padding(14.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(app.label, style = Field.type.bodyStrong, color = Field.colors.ink)
            }
        }
    }
}

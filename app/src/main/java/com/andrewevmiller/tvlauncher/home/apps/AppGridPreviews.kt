package com.andrewevmiller.tvlauncher.home.apps

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.FakeAppsRepository
import com.andrewevmiller.tvlauncher.home.data.FieldLayout
import com.andrewevmiller.tvlauncher.home.data.InMemoryLayoutStore
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.dotGrid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val PreviewLabels = listOf(
    "YouTube", "Netflix", "Plex", "Spotify", "Tidal", "Moonlight", "RetroArch", "File Manager",
    "Analyzer", "Prime Video", "Disney+", "Twitch", "YouTube Music", "Steam Link", "Downloader",
)

private fun previewApps(count: Int): List<LauncherApp> = List(count) { i ->
    LauncherApp(
        component = ComponentName("preview.app$i", "Main"),
        label = PreviewLabels[i % PreviewLabels.size],
        category = AppCategory.values()[i % AppCategory.values().size],
        artwork = null,
        artworkIsBanner = false,
    )
}

/** [installed] apps, the first [pinned] of them pinned, in a fake repository. */
private fun previewRepository(installed: Int, pinned: Int): FakeAppsRepository {
    val apps = previewApps(installed)
    return FakeAppsRepository(
        installed = apps,
        store = InMemoryLayoutStore(
            FieldLayout(pinned = apps.take(pinned).map { it.component.flattenToString() }, seeded = true),
        ),
    )
}

@Composable
private fun PreviewPage(content: @Composable () -> Unit) {
    FieldTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Field.colors.bg)
                .dotGrid(Field.colors.line)
                .padding(horizontal = FieldDimens.safeHorizontal, vertical = FieldDimens.safeVertical),
        ) { content() }
    }
}

@Composable
private fun HomeGridPreview(pinned: Int) {
    val repository = remember { previewRepository(installed = 15, pinned = pinned) }
    val favorites = remember { runBlocking { repository.favorites.first() } }
    PreviewPage {
        AppGridSection(
            apps = favorites,
            onLaunch = {},
            onOpenDrawer = {},
            onMove = { _, _ -> },
            onUnpin = {},
            onHide = {},
            installedCount = 15,
        )
    }
}

/** 6 pinned apps and the All apps cell: 7 cells, 2 grid rows. */
@Preview(device = "id:tv_1080p")
@Composable
private fun AppGridSixAppsPreview() {
    HomeGridPreview(pinned = 6)
}

/** 9 pinned apps and the All apps cell: 10 cells, 3 grid rows (the last holds 2). */
@Preview(device = "id:tv_1080p")
@Composable
private fun AppGridNineAppsPreview() {
    HomeGridPreview(pinned = 9)
}

@Preview(device = "id:tv_1080p")
@Composable
private fun AppDrawerPreview() {
    val repository = remember { previewRepository(installed = 15, pinned = 6) }
    val apps = remember { runBlocking { repository.apps.first() } }
    val favorites = remember { runBlocking { repository.favorites.first() } }
    FieldTheme {
        AppDrawer(
            apps = apps,
            onLaunch = {},
            onBack = {},
            pinned = favorites.map { it.component }.toSet(),
            onPin = {},
            onUnpin = {},
            onHide = {},
        )
    }
}

/** The tile states side by side: banner (empty bitmap here), icon-less, lifted in edit mode, All apps. */
@Preview(device = "id:tv_1080p")
@Composable
private fun AppTileStatesPreview() {
    val apps = remember { previewApps(3) }
    val banner = remember { ImageBitmap(320, 180) }
    PreviewPage {
        Row(horizontalArrangement = Arrangement.spacedBy(FieldDimens.appGridColumnGap)) {
            AppTile(app = apps[0].copy(artwork = banner, artworkIsBanner = true), onLaunch = {})
            AppTile(app = apps[1], onLaunch = {})
            AppTile(app = apps[2], onLaunch = {}, lifted = true)
            AllAppsTile(onOpen = {}, installedCount = 15)
        }
    }
}

package com.andrewevmiller.tvlauncher.ui

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import com.andrewevmiller.tvlauncher.HomeDeps
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.home.HomeContent
import com.andrewevmiller.tvlauncher.home.HomeUiState
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.AppsRepository
import com.andrewevmiller.tvlauncher.home.data.FakeAppsRepository
import com.andrewevmiller.tvlauncher.home.data.InMemoryLaunchHistory
import com.andrewevmiller.tvlauncher.home.data.LaunchEntry
import com.andrewevmiller.tvlauncher.home.data.LaunchHistory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Home screen at the Hisense's geometry (1920x1080 px at density 320 = a 960x540dp canvas)
 * and writes PNGs to app/build/screenshots/, so layout, fonts, the dot grid and the focus ring can be looked at
 * without a TV. This is a visual aid, not an assertion: it fails only if rendering itself breaks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class HomeScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private class Deps(
        override val settings: SettingsRepository,
        override val apps: AppsRepository,
        override val launchHistory: LaunchHistory,
        override val screensaver: ScreensaverController = NoOpScreensaverController,
    ) : HomeDeps

    private fun app(pkg: String, label: String, category: AppCategory) =
        LauncherApp(ComponentName(pkg, "$pkg.Main"), label, category, artwork = null, artworkIsBanner = false)

    private val apps = listOf(
        app("com.google.android.youtube.tv", "YouTube", AppCategory.VIDEO),
        app("com.netflix.ninja", "Netflix", AppCategory.VIDEO),
        app("com.plexapp.android", "Plex", AppCategory.VIDEO),
        app("com.spotify.tv.android", "Spotify", AppCategory.MUSIC),
        app("com.aspiro.tidal", "Tidal", AppCategory.MUSIC),
        app("com.limelight", "Moonlight", AppCategory.GAMES),
        app("com.retroarch", "RetroArch", AppCategory.GAMES),
        app("com.alphainventor.filemanager", "File Manager", AppCategory.TOOLS),
        app("com.andrewevmiller.analyzer", "Analyzer", AppCategory.TOOLS),
    )

    private fun shot(name: String, settings: LauncherSettings = LauncherSettings(), expandRecent: Boolean = false) {
        val repo = FakeAppsRepository(installed = apps)
        val history = InMemoryLaunchHistory(
            listOf(
                LaunchEntry(apps[2].component.flattenToString(), "Plex", AppCategory.VIDEO, 1_792_605_900_000L),
                LaunchEntry(apps[3].component.flattenToString(), "Spotify", AppCategory.MUSIC, 1_792_519_500_000L),
                LaunchEntry(apps[5].component.flattenToString(), "Moonlight", AppCategory.GAMES, 1_792_433_100_000L),
            ),
        )
        val deps = Deps(FakeSettingsRepository(settings), repo, history)
        val ui = HomeUiState().also { it.recentExpanded = expandRecent }
        // The clock header ticks forever (while(true) { delay(60 s) }), which waitForIdle would wait on for ever:
        // drive the frame clock by hand instead.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FieldTheme(dark = settings.darkMode, reducedMotion = true) {
                HomeContent(deps = deps, ui = ui, settings = settings, onOpenDrawer = {}, onOpenSettings = {})
            }
        }
        repeat(40) { rule.mainClock.advanceTimeByFrame() }
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot $name: ${image.width}x${image.height}")
    }

    @Test fun homeDark() = shot("home-dark")

    @Test fun homeDarkRecentOpen() = shot("home-dark-recent", expandRecent = true)

    @Test fun homeLight() = shot("home-light", LauncherSettings(darkMode = false))
}

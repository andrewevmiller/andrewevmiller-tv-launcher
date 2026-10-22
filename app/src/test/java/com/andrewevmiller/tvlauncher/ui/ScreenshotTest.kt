package com.andrewevmiller.tvlauncher.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.andrewevmiller.tvlauncher.HomeDeps
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.home.HomeCategoryChips
import com.andrewevmiller.tvlauncher.home.HomeContent
import com.andrewevmiller.tvlauncher.home.HomeUiState
import com.andrewevmiller.tvlauncher.home.apps.AppDrawerScreen
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.AppsRepository
import com.andrewevmiller.tvlauncher.home.data.FakeAppsRepository
import com.andrewevmiller.tvlauncher.home.data.FieldLayout
import com.andrewevmiller.tvlauncher.home.data.InMemoryLaunchHistory
import com.andrewevmiller.tvlauncher.home.data.InMemoryLayoutStore
import com.andrewevmiller.tvlauncher.home.data.LaunchEntry
import com.andrewevmiller.tvlauncher.home.data.LaunchHistory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.safety.FallbackNotice
import com.andrewevmiller.tvlauncher.safety.FallbackNoticeBanner
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import com.andrewevmiller.tvlauncher.settings.SettingsScreen
import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
import com.andrewevmiller.tvlauncher.sound.MotifOverride
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.ui.components.ChipsRow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens to PNGs (see [ScreenshotSupport]). Run with FIELD_SCREENSHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

    private class Deps(
        override val settings: SettingsRepository,
        override val apps: AppsRepository,
        override val launchHistory: LaunchHistory,
        override val screensaver: ScreensaverController = NoOpScreensaverController,
    ) : HomeDeps

    private fun app(pkg: String, label: String, category: AppCategory) =
        LauncherApp(ComponentName(pkg, "$pkg.Main"), label, category, artwork = null, artworkIsBanner = false)

    private val installed = listOf(
        app("com.google.android.youtube.tv", "YouTube", AppCategory.VIDEO),
        app("com.netflix.ninja", "Netflix", AppCategory.VIDEO),
        app("com.plexapp.android", "Plex", AppCategory.VIDEO),
        app("com.spotify.tv.android", "Spotify", AppCategory.MUSIC),
        app("com.aspiro.tidal", "Tidal", AppCategory.MUSIC),
        app("com.limelight", "Moonlight", AppCategory.GAMES),
        app("com.retroarch", "RetroArch", AppCategory.GAMES),
        app("com.alphainventor.filemanager", "File Manager", AppCategory.TOOLS),
        app("com.andrewevmiller.analyzer", "Analyzer", AppCategory.TOOLS),
        app("tv.twitch.android.app", "Twitch", AppCategory.VIDEO),
        app("com.android.vending", "Downloader", AppCategory.TOOLS),
    )

    private fun repo() = FakeAppsRepository(
        installed = installed,
        store = InMemoryLayoutStore(FieldLayout(pinned = installed.take(9).map { it.component.flattenToString() }, seeded = true)),
    )

    private fun history() = InMemoryLaunchHistory(
        listOf(
            LaunchEntry(installed[2].component.flattenToString(), "Plex", AppCategory.VIDEO, 1_792_605_900_000L),
            LaunchEntry(installed[3].component.flattenToString(), "Spotify", AppCategory.MUSIC, 1_792_519_500_000L),
            LaunchEntry(installed[5].component.flattenToString(), "Moonlight", AppCategory.GAMES, 1_792_433_100_000L),
        ),
    )

    private fun shot(name: String, settings: LauncherSettings = LauncherSettings(), content: @Composable (LauncherSettings) -> Unit) {
        rule.setContent { FieldTheme(dark = settings.darkMode, reducedMotion = true) { content(settings) } }
        rule.waitForIdle()
        val file = ScreenshotSupport.capture(rule.activity, name)
        println("screenshot ${file.path}")
    }

    @Test fun homeDark() = shot("home-dark") {
        HomeContent(Deps(FakeSettingsRepository(it), repo(), history()), HomeUiState(), it, {}, {})
    }

    @Test fun homeDarkRecentOpen() = shot("home-dark-recent-open") {
        HomeContent(Deps(FakeSettingsRepository(it), repo(), history()), HomeUiState().also { s -> s.recentExpanded = true }, it, {}, {})
    }

    @Test fun homeLight() = shot("home-light", LauncherSettings(darkMode = false)) {
        HomeContent(Deps(FakeSettingsRepository(it), repo(), history()), HomeUiState(), it, {}, {})
    }

    @Test fun homeFilteredToMusic() = shot("home-dark-filter-music") {
        HomeContent(Deps(FakeSettingsRepository(it), repo(), history()), HomeUiState().also { s -> s.category = AppCategory.MUSIC }, it, {}, {})
    }

    @Test fun drawer() = shot("drawer-dark") {
        AppDrawerScreen(
            repository = repo(),
            onBack = {},
            header = { ChipsRow(options = HomeCategoryChips, selected = null, onSelect = {}) },
        )
    }

    @Test fun settings() = shot("settings-dark") {
        SettingsScreen(
            settings = FakeSettingsRepository(it),
            overrides = FakeMotifOverrides(mapOf(installed[2].component.packageName to MotifOverride(rerollCount = 2))),
            apps = repo(),
            screensaver = NoOpScreensaverController,
            onBack = {},
        )
    }

    @Test fun crashNotice() = shot("home-dark-notice") {
        Box(Modifier) {
            HomeContent(Deps(FakeSettingsRepository(it), repo(), history()), HomeUiState(), it, {}, {}, holdFocus = true)
            FallbackNoticeBanner(
                notice = FallbackNotice(
                    message = "Field Launcher crashed twice, so Home went back to Wii Menu. Run scripts/deploy.sh to make it Home again.",
                    previousHome = "Wii Menu",
                ),
                onDismiss = {},
                modifier = Modifier.then(Modifier),
            )
        }
    }
}

/** The whole Home page in one frame: a tall window (same width and density as the TV) so nothing is below the fold. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h1300dp-xhdpi")
class TallScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

    private fun app(pkg: String, label: String, category: AppCategory) =
        LauncherApp(ComponentName(pkg, "$pkg.Main"), label, category, artwork = null, artworkIsBanner = false)

    private fun render(name: String, settings: LauncherSettings, expandRecent: Boolean) {
        val installed = listOf(
            app("com.google.android.youtube.tv", "YouTube", AppCategory.VIDEO),
            app("com.netflix.ninja", "Netflix", AppCategory.VIDEO),
            app("com.plexapp.android", "Plex", AppCategory.VIDEO),
            app("com.spotify.tv.android", "Spotify", AppCategory.MUSIC),
            app("com.aspiro.tidal", "Tidal", AppCategory.MUSIC),
            app("com.limelight", "Moonlight", AppCategory.GAMES),
            app("com.retroarch", "RetroArch", AppCategory.GAMES),
        )
        val repo = FakeAppsRepository(
            installed = installed,
            store = InMemoryLayoutStore(FieldLayout(pinned = installed.map { it.component.flattenToString() }, seeded = true)),
        )
        val history = InMemoryLaunchHistory(
            listOf(
                LaunchEntry(installed[2].component.flattenToString(), "Plex", AppCategory.VIDEO, 1_792_605_900_000L),
                LaunchEntry(installed[3].component.flattenToString(), "Spotify", AppCategory.MUSIC, 1_792_519_500_000L),
                LaunchEntry(installed[5].component.flattenToString(), "Moonlight", AppCategory.GAMES, 1_792_433_100_000L),
            ),
        )
        val deps = object : HomeDeps {
            override val settings: SettingsRepository = FakeSettingsRepository(settings)
            override val apps: AppsRepository = repo
            override val launchHistory: LaunchHistory = history
            override val screensaver: ScreensaverController = NoOpScreensaverController
        }
        rule.setContent {
            FieldTheme(dark = settings.darkMode, reducedMotion = true) {
                HomeContent(deps, HomeUiState().also { it.recentExpanded = expandRecent }, settings, {}, {}, holdFocus = true)
            }
        }
        rule.waitForIdle()
        println("screenshot " + ScreenshotSupport.capture(rule.activity, name).path)
    }

    @Test fun tallDark() = render("home-dark-tall", LauncherSettings(), expandRecent = false)

    @Test fun tallDarkRecentOpen() = render("home-dark-tall-recent-open", LauncherSettings(), expandRecent = true)
}

/** The three artwork cases of an app tile: a real 16:9 banner, a square app icon, and neither (category glyph). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ArtworkScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

    /** A stand-in for a leanback banner: 320x180, a colour wash with a big shape, like real banners. */
    private fun banner(from: Int, to: Int): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 320f, 180f, from, to, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 320f, 180f, p)
        p.shader = null
        p.color = 0xCCFFFFFF.toInt()
        c.drawCircle(235f, 70f, 46f, p)
        p.color = 0x55000000
        c.drawRect(0f, 120f, 320f, 180f, p)
        return bmp.asImageBitmap()
    }

    /** A stand-in for a launcher icon: 192x192 rounded square with a glyph-like mark. */
    private fun icon(color: Int): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = color
        c.drawRoundRect(12f, 12f, 180f, 180f, 40f, 40f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(96f, 96f, 44f, p)
        return bmp.asImageBitmap()
    }

    private fun app(pkg: String, label: String, category: AppCategory, art: androidx.compose.ui.graphics.ImageBitmap?, isBanner: Boolean) =
        LauncherApp(ComponentName(pkg, "$pkg.Main"), label, category, art, isBanner)

    @Test fun artworkCases() {
        val apps = listOf(
            app("a.banner1", "Banner app", AppCategory.VIDEO, banner(0xFF5B3FA0.toInt(), 0xFF1F6F8B.toInt()), true),
            app("a.banner2", "Another banner", AppCategory.VIDEO, banner(0xFFB4532A.toInt(), 0xFF6B2D5C.toInt()), true),
            app("a.icon1", "Icon only", AppCategory.MUSIC, icon(0xFFE0772C.toInt()), false),
            app("a.icon2", "Square icon", AppCategory.GAMES, icon(0xFF2C7BE0.toInt()), false),
            app("a.none", "No artwork", AppCategory.TOOLS, null, false),
        )
        val repo = FakeAppsRepository(
            installed = apps,
            store = InMemoryLayoutStore(FieldLayout(pinned = apps.map { it.component.flattenToString() }, seeded = true)),
        )
        rule.setContent {
            FieldTheme(dark = true, reducedMotion = true) {
                Box(
                    Modifier
                        .androidx_fill()
                        .padding(horizontal = com.andrewevmiller.tvlauncher.theme.FieldDimens.safeHorizontal, vertical = com.andrewevmiller.tvlauncher.theme.FieldDimens.safeVertical),
                ) {
                    com.andrewevmiller.tvlauncher.home.apps.AppsSection(repository = repo, category = null, onOpenDrawer = {})
                }
            }
        }
        rule.waitForIdle()
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "tiles-artwork").path)
    }
}

private fun Modifier.androidx_fill(): Modifier =
    this.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF171716))

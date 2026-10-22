package com.andrewevmiller.tvlauncher.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SleepTools
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.FakeAppsRepository
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsScreen
import com.andrewevmiller.tvlauncher.sleep.FakeSleepTimerController
import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.content.ComponentName

/** Sleep timer screens to PNGs (see [ScreenshotSupport]). Run with FIELD_SCREENSHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SleepToolsScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

    private val now = 1_000_000L
    private val minute = 60_000L

    private fun app(pkg: String, label: String, category: AppCategory) =
        LauncherApp(ComponentName(pkg, "$pkg.Main"), label, category, artwork = null, artworkIsBanner = false)

    private val installed = listOf(
        app("com.netflix.ninja", "Netflix", AppCategory.VIDEO),
        app("com.plexapp.android", "Plex", AppCategory.VIDEO),
        app("com.spotify.tv.android", "Spotify", AppCategory.MUSIC),
    )

    @Composable
    private fun Home(timer: FakeSleepTimerController, open: Boolean) {
        HomeScreen(
            allApps = installed,
            systemTiles = listOf(
                SystemTile("HDMI 1", FieldIcons.Port) {},
                SystemTile("Wi-Fi", FieldIcons.Wifi) {},
                SystemTile("Search", FieldIcons.Search) {},
                SystemTile("Settings", FieldIcons.Gear) {},
            ),
            chip = Chip.ALL, drawer = false, onChip = {}, onOpenDrawer = {}, onLaunch = {},
            feature = null, recent = emptyList(), recentOpen = false, onToggleRecent = {},
            order = emptyList(), editingPkg = null, onLongPress = {}, onMove = { _, _ -> }, onEditEnd = {},
            pinnedSet = installed.map { it.component.packageName }.toSet(),
            menuApp = null, onMenuPick = {},
            tools = { SleepTools(timer, defaultMinutes = 45, open = open, onOpenChange = {}, now = { now }) },
        )
    }

    private fun homeShot(name: String, open: Boolean, runningMinutes: Int?) {
        val timer = FakeSleepTimerController { now }
        if (runningMinutes != null) timer.start(runningMinutes)
        rule.setContent { FieldTheme(reducedMotion = true) { Home(timer, open) } }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Tools"))
        rule.waitForIdle()
        // The picker was open from the first frame, so nothing scrolled for it; scroll as Home does when it opens.
        if (open) rule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        rule.waitForIdle()
        println("screenshot " + ScreenshotSupport.capture(rule.activity, name).path)
    }

    @Test fun toolsIdle() = homeShot("sleep-home-idle", open = false, runningMinutes = null)

    @Test fun toolsRunning() = homeShot("sleep-home-running", open = false, runningMinutes = 42)

    @Test fun pickerIdle() = homeShot("sleep-picker-idle", open = true, runningMinutes = null)

    @Test fun pickerRunning() = homeShot("sleep-picker-running", open = true, runningMinutes = 30)

    @Test fun settingsSleepSection() {
        rule.setContent {
            FieldTheme(reducedMotion = true) {
                SettingsScreen(
                    settings = FakeSettingsRepository(LauncherSettings(sleepMinutes = 60)),
                    overrides = FakeMotifOverrides(),
                    apps = FakeAppsRepository(),
                    screensaver = NoOpScreensaverController,
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Start now"))
        rule.waitForIdle()
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "sleep-settings").path)
    }
}

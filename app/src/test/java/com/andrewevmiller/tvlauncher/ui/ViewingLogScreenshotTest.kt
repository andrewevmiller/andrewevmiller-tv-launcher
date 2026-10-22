package com.andrewevmiller.tvlauncher.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SleepTools
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.sleep.FakeSleepTimerController
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.viewinglog.ViewingSession
import com.andrewevmiller.tvlauncher.viewinglog.ui.ViewingLogScreen
import com.andrewevmiller.tvlauncher.viewinglog.ui.ViewingLogTile
import android.content.ComponentName
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Viewing log page to PNGs: a filled week, and the empty state that says how to grant usage access. Run with
 * FIELD_SCREENSHOTS=1 (see [ScreenshotSupport]); the PNGs go to app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ViewingLogScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-22")
    private val labels = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.plexapp.android" to "Plex",
        "com.spotify.tv.android" to "Spotify",
        "com.netflix.ninja" to "Netflix",
        "com.aspiro.tidal" to "Tidal",
    )

    private fun session(pkg: String, day: String, from: String, to: String) = ViewingSession(
        pkg,
        LocalDate.parse(day).atTime(LocalTime.parse(from)).toInstant(ZoneOffset.UTC).toEpochMilli(),
        LocalDate.parse(day).atTime(LocalTime.parse(to)).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    /** A believable week plus the earlier fortnight, so the month chip and the sessions list have some length. */
    private val filled = listOf(
        session("com.google.android.youtube.tv", "2026-10-19", "19:00", "20:30"),
        session("com.spotify.tv.android", "2026-10-19", "17:00", "17:50"),
        session("com.spotify.tv.android", "2026-10-20", "21:40", "23:05"),
        session("com.netflix.ninja", "2026-10-20", "19:30", "21:30"),
        session("com.google.android.youtube.tv", "2026-10-20", "12:30", "13:10"),
        session("com.google.android.youtube.tv", "2026-10-21", "19:05", "20:40"),
        session("com.plexapp.android", "2026-10-21", "18:05", "18:58"),
        session("com.spotify.tv.android", "2026-10-21", "12:10", "12:45"),
        session("com.plexapp.android", "2026-10-22", "10:00", "10:45"),
        session("com.aspiro.tidal", "2026-10-22", "08:00", "08:40"),
        session("com.netflix.ninja", "2026-10-14", "20:00", "22:00"),
        session("com.google.android.youtube.tv", "2026-10-12", "19:30", "20:15"),
    )

    private fun page(sessions: List<ViewingSession>, granted: Boolean, enabled: Boolean = true) {
        rule.setContent {
            FieldTheme(reducedMotion = true) {
                ViewingLogScreen(
                    sessions = sessions,
                    enabled = enabled,
                    usageGranted = granted,
                    labelOf = { labels[it] ?: it },
                    onBack = {},
                    zone = zone,
                    today = { today },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun filledWeek() {
        page(filled, granted = true)
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "viewing-log-filled").path)
    }

    @Test
    fun filledWeekScrolledToSessions() {
        page(filled, granted = true)
        rule.onNode(hasScrollAction()).performScrollToIndex(15)
        rule.waitForIdle()
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "viewing-log-sessions").path)
    }

    @Test
    fun emptyNeedsUsageAccess() {
        page(emptyList(), granted = false)
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "viewing-log-empty").path)
    }

    @Test
    fun logIsOff() {
        page(emptyList(), granted = true, enabled = false)
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "viewing-log-off").path)
    }

    private fun app(pkg: String, label: String, category: AppCategory) =
        LauncherApp(ComponentName(pkg, "$pkg.Main"), label, category, artwork = null, artworkIsBanner = false)

    /** The Tools row on Home with the Viewing log tile beside the sleep tile, scrolled into view as Home does. */
    @Test
    fun homeToolsRow() {
        val now = 1_000_000L
        val installed = listOf(
            app("com.netflix.ninja", "Netflix", AppCategory.VIDEO),
            app("com.plexapp.android", "Plex", AppCategory.VIDEO),
            app("com.spotify.tv.android", "Spotify", AppCategory.MUSIC),
        )
        rule.setContent {
            FieldTheme(reducedMotion = true) {
                HomeScreen(
                    allApps = installed,
                    systemTiles = listOf(
                        SystemTile("HDMI 1", FieldIcons.Port) {},
                        SystemTile("Wi-Fi", FieldIcons.Wifi) {},
                        SystemTile("Search", FieldIcons.Search) {},
                        SystemTile("Settings", FieldIcons.Gear) {},
                    ),
                    chip = Chip.ALL, drawer = false, onChip = {}, onOpenDrawer = {}, onLaunch = {},
                    features = emptyList(), nowPlaying = null, onOpenPlaying = {}, recent = emptyList(), recentOpen = false, onToggleRecent = {},
                    order = emptyList(), editingPkg = null, onLongPress = {}, onMove = { _, _ -> }, onEditEnd = {},
                    pinnedSet = installed.map { it.component.packageName }.toSet(),
                    menuApp = null, onMenuPick = {},
                    tools = {
                        SleepTools(
                            controller = FakeSleepTimerController { now },
                            defaultMinutes = 45,
                            open = false,
                            onOpenChange = {},
                            now = { now },
                            trailing = { ViewingLogTile(onClick = {}, focusRequest = 0) },
                        )
                    },
                )
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Tools"))
        rule.waitForIdle()
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "viewing-log-home-tools").path)
    }
}

package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SleepTools
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.sleep.FakeSleepTimerController
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.viewinglog.InMemoryViewingLog
import com.andrewevmiller.tvlauncher.viewinglog.ViewingLogText
import com.andrewevmiller.tvlauncher.viewinglog.ViewingSession
import com.andrewevmiller.tvlauncher.viewinglog.ui.ViewingLogScreen
import com.andrewevmiller.tvlauncher.viewinglog.ui.ViewingLogTile
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Viewing log page with fake sessions: the summary and day rows, the range chips, the empty and off states,
 * and Back returning to Home with focus on the Tools tile it came from. Today is Thursday 2026-10-22 (UTC), and the
 * fixed sessions below make every figure a hand-checked sum.
 *
 * The page is a lazy list, so rows below the fold are not in the semantics tree until scrolled to: [shown] checks
 * what is composed, and [scrollTo] brings an item in by its index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class ViewingLogBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-22")
    private val youtube = "com.google.android.youtube.tv"
    private val plex = "com.plexapp.android"
    private val spotify = "com.spotify.tv.android"
    private val netflix = "com.netflix.ninja"
    private val labels = mapOf(youtube to "YouTube", plex to "Plex", spotify to "Spotify", netflix to "Netflix")

    /** This week (10-19..10-25): 90 + 85 + 95 + 53 + 45 = 368 min in 5 sessions. Last week: 120 min, one session. */
    private val week = listOf(
        session(youtube, "2026-10-19", "19:00", "20:30"),
        session(spotify, "2026-10-20", "21:40", "23:05"),
        session(youtube, "2026-10-21", "19:05", "20:40"),
        session(plex, "2026-10-21", "18:05", "18:58"),
        session(plex, "2026-10-22", "10:00", "10:45"),
        session(netflix, "2026-10-14", "20:00", "22:00"),
    )
    private val log = InMemoryViewingLog(week)

    private fun session(pkg: String, day: String, from: String, to: String) = ViewingSession(
        pkg,
        LocalDate.parse(day).atTime(LocalTime.parse(from)).toInstant(ZoneOffset.UTC).toEpochMilli(),
        LocalDate.parse(day).atTime(LocalTime.parse(to)).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    private fun show(sessions: List<ViewingSession> = week, enabled: Boolean = true, granted: Boolean = true) {
        rule.setContentWithSound(sound) {
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
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    /** True when the text is in the composed tree (rows below the fold are not until scrolled to). */
    private fun shown(text: String) {
        assertTrue("\"$text\" should be on screen", rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())
    }

    private fun notShown(text: String) {
        assertTrue("\"$text\" should not be on screen", rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    }

    /**
     * Scrolls the page so that list item [index] is composed. The items are: 0 title, 1 summary, 2 chips, 3 the
     * bars header (or the grant note), then the bars, Top apps, Sessions and each day.
     */
    private fun scrollTo(index: Int) {
        rule.onNode(hasScrollAction()).performScrollToIndex(index)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    fun theSummaryAndDayRowsComeFromTheSessions() {
        show()

        rule.onNodeWithText("THIS WEEK  6 h 08 m · 5 sessions").assertExists()
        shown("PER DAY")
        // One bar row per weekday, with that day's time: Mon 90 m, Tue 85 m, Wed 148 m, Thu 45 m.
        shown("1 h 30 m")
        shown("1 h 25 m")
        shown("2 h 28 m")
        shown("Mon")
        shown("Thu")
    }

    @Test
    fun topAppsAndSessionsByDayAreListed() {
        show()

        scrollTo(11)           // the Top apps header
        shown("TOP APPS")
        shown("3 h 05 m")      // YouTube: 90 + 95
        shown("50%")           // 185 of 368

        scrollTo(15)           // the Sessions header
        shown("SESSIONS")
        shown("10-21")         // a day heading
        shown("19:05–20:40")   // a session row
    }

    @Test
    fun theChipsSwitchTheRange() {
        show()
        rule.assertFocused("This week")

        rule.focus("Last week")
        rule.pressOk()

        rule.onNodeWithText("LAST WEEK  2 h 00 m · 1 session").assertExists()
        rule.onNodeWithText("THIS WEEK  6 h 08 m · 5 sessions").assertDoesNotExist()
        shown("Netflix")
        notShown("YouTube")
    }

    @Test
    fun theMonthChipShowsWeeklyBars() {
        show()

        rule.focus("Month")
        rule.pressOk()

        rule.onNodeWithText("MONTH  8 h 08 m · 6 sessions").assertExists()
        shown("PER WEEK · STARTING")
        scrollTo(8)
        shown("10-26")         // the last weekly bar
    }

    @Test
    fun aMissingGrantSaysHowToGrantItAndAnEmptyRangeSaysSo() {
        show(sessions = emptyList(), granted = false)

        shown("No sessions recorded yet.")
        shown("Usage access is not granted, so nothing new is recorded.")
        shown(ViewingLogText.GRANT_COMMAND)
    }

    @Test
    fun aGrantedEmptyLogDoesNotAskForTheGrant() {
        show(sessions = emptyList(), granted = true)

        shown("No sessions recorded yet.")
        notShown(ViewingLogText.GRANT_COMMAND)
    }

    @Test
    fun aLogThatIsOffSaysSoAndShowsNoFigures() {
        show(enabled = false)

        shown("Viewing log is off.")
        shown("Turn it on in Settings, under Viewing log.")
        notShown("THIS WEEK  6 h 08 m · 5 sessions")
    }

    @Test
    fun backLeavesTheLog() {
        var backs = 0
        rule.setContentWithSound(sound) {
            ViewingLogScreen(
                sessions = week,
                enabled = true,
                usageGranted = true,
                labelOf = { labels[it] ?: it },
                onBack = { backs++ },
                zone = zone,
                today = { today },
            )
        }
        rule.waitForIdle()

        rule.pressBack()

        assertEquals(1, backs)
    }

    // ---- Back to Home with focus on the Tools tile ------------------------------------------------------------

    private var route by mutableStateOf("home")
    private var logReturns by mutableStateOf(0)

    /**
     * The FieldRoot wiring for the log, in miniature: Home stays composed under the log but cannot take focus, the
     * Tools row carries the Viewing log tile beside the sleep tile, and each return from the log counts up so the
     * tile takes focus again.
     */
    @Composable
    private fun Root() {
        val sessions by log.sessions.collectAsState()
        val fx = remember { RepoFixture(numberedApps(4)) }
        Box(Modifier.fillMaxSize()) {
            HomeScreen(
                allApps = fx.installed,
                systemTiles = listOf(SystemTile("Wi-Fi", FieldIcons.Wifi) {}),
                chip = Chip.ALL,
                drawer = false,
                onChip = {},
                onOpenDrawer = {},
                onLaunch = {},
                feature = null,
                recent = emptyList(),
                recentOpen = false,
                onToggleRecent = {},
                order = emptyList(),
                editingPkg = null,
                onLongPress = {},
                onMove = { _, _ -> },
                onEditEnd = {},
                pinnedSet = fx.installed.map { it.component.packageName }.toSet(),
                menuApp = null,
                onMenuPick = {},
                modifier = if (route == "home") Modifier else Modifier.focusProperties { canFocus = false },
                tools = {
                    SleepTools(
                        controller = FakeSleepTimerController { 1_000_000L },
                        defaultMinutes = 45,
                        open = false,
                        onOpenChange = {},
                        now = { 1_000_000L },
                        trailing = {
                            ViewingLogTile(onClick = { route = "log" }, focusRequest = logReturns)
                        },
                    )
                },
            )
            if (route == "log") {
                ViewingLogScreen(
                    sessions = sessions,
                    enabled = true,
                    usageGranted = true,
                    labelOf = { labels[it] ?: it },
                    onBack = {
                        logReturns++
                        route = "home"
                    },
                    zone = zone,
                    today = { today },
                )
            }
        }
    }

    @Test
    fun theLogTileSitsBesideTheSleepTimerAndBackReturnsFocusToIt() {
        route = "home"
        logReturns = 0
        rule.setContentWithSound(sound) { Root() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        rule.focus("Wi-Fi")
        rule.pressDown()
        rule.assertFocused("Sleep timer")
        rule.pressRight()
        rule.assertFocused("Viewing log")

        rule.pressOk()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("THIS WEEK  6 h 08 m · 5 sessions").assertExists()
        rule.assertFocused("This week")

        rule.pressBack()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        rule.onNodeWithText("THIS WEEK  6 h 08 m · 5 sessions").assertDoesNotExist()
        rule.assertFocused("Viewing log")
    }
}

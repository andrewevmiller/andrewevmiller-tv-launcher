package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SleepTools
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsScreen
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.sleep.FakeSleepTimerController
import com.andrewevmiller.tvlauncher.sleep.SleepTimerState
import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Home Tools row (Sleep timer tile and inline picker) inside the real [HomeScreen], and the Settings "Default
 * length" chips. The host below repeats FieldHome's wiring for the picker: one `open` flag, and a Back handler that
 * closes it (FieldHome itself needs a Context, installed-app scan and AppContainer, which a JVM test cannot build).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class SleepToolsBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private val minute = 60_000L
    private var nowMs = 1_000_000L
    private val timer = FakeSleepTimerController { nowMs }
    private val repo = FakeSettingsRepository(LauncherSettings(reducedMotion = true))
    private var open by mutableStateOf(false)
    private var route by mutableStateOf("home")

    @Composable
    private fun Host() {
        val settings by repo.settings.collectAsState(initial = LauncherSettings())
        if (route == "settings") {
            SettingsScreen(
                settings = repo,
                overrides = FakeMotifOverrides(),
                apps = RepoFixture(numberedApps(3)).repo,
                screensaver = NoOpScreensaverController,
                onBack = { route = "home" },
            )
            return
        }
        BackHandler(enabled = open) {
            open = false
            sound.back()
        }
        val fixture = remember { RepoFixture(numberedApps(4)) }
        HomeScreen(
            allApps = fixture.installed,
            systemTiles = listOf(
                SystemTile("Wi-Fi", FieldIcons.Wifi) {},
                SystemTile("Settings", FieldIcons.Gear) {},
            ),
            chip = Chip.ALL,
            drawer = false,
            onChip = {},
            onOpenDrawer = {},
            onLaunch = {},
            features = emptyList(),
            nowPlaying = null,
            onOpenPlaying = {},
            recent = emptyList(),
            recentOpen = false,
            onToggleRecent = {},
            order = emptyList(),
            editingPkg = null,
            onLongPress = {},
            onMove = { _, _ -> },
            onEditEnd = {},
            pinnedSet = fixture.installed.map { it.component.packageName }.toSet(),
            menuApp = null,
            onMenuPick = {},
            tools = {
                SleepTools(
                    controller = timer,
                    defaultMinutes = settings.sleepMinutes,
                    open = open,
                    onOpenChange = { open = it },
                    now = { nowMs },
                )
            },
        )
    }

    private fun show() {
        rule.setContentWithSound(sound) { Host() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun running() = timer.state.value as SleepTimerState.Running

    /** From the Wi-Fi tile, Down reaches the Sleep timer tile. */
    private fun toTile() {
        rule.focus("Wi-Fi")
        rule.pressDown()
    }

    private fun openPicker() {
        toTile()
        rule.pressOk()
    }

    /** Walks Down the Settings list into the Sleep chips. Down from "Start now" lands on the nearest chip, 120. */
    private fun goToSettingsChips() {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        var steps = 0
        while (rule.focusedTexts().firstOrNull() != "120") {
            check(steps++ < 40) { "never reached the Sleep chips" }
            rule.pressDown()
        }
    }

    private fun assertPickerGone() = rule.onNodeWithText("Sleep after · min").assertDoesNotExist()

    @Test
    fun theTileIsInTheToolsRowAndDownFromTheSystemTilesReachesIt() {
        show()
        rule.onNodeWithText("Tools").assertExists()

        toTile()

        rule.assertFocused("Sleep timer")
        assertEquals(listOf("Sleep timer", "Off"), rule.focusedTexts())
    }

    @Test
    fun okOpensThePickerWithFocusOnTheDefaultLength() {
        show()
        toTile()
        sound.clear()

        rule.pressOk()

        rule.onNodeWithText("Sleep after · min").assertExists()
        rule.assertFocused("45")
        listOf("15", "30", "60", "90", "120").forEach { rule.onNode(focusableWithText(it)).assertExists() }
        rule.onAllNodes(focusableWithText("Off")).assertCountEquals(2) // the tile's caption and the chip
        rule.onNodeWithText("Cancel").assertDoesNotExist()
        rule.onNodeWithText("+15 min").assertDoesNotExist()
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun theExpandedPickerIsScrolledIntoView() {
        show()
        toTile()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        fun tileTop() = rule.onNode(focusableWithText("Sleep timer")).fetchSemanticsNode().boundsInRoot.top
        val before = tileTop()

        rule.pressOk()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        rule.onNodeWithText("120").assertIsDisplayed()
        rule.onNodeWithText("Sleep after · min").assertIsDisplayed()
        // The picker grew the last item, so the page scrolled up to keep it inside the bottom margin.
        assertTrue("tile moved from $before to ${tileTop()}", before - tileTop() > 40f)
    }

    @Test
    fun pickingThirtyStartsTheTimerCollapsesThePickerAndReturnsFocusToTheTile() {
        show()
        openPicker()
        rule.pressLeft()
        rule.assertFocused("30")
        sound.clear()

        rule.pressOk()

        assertEquals(30 * minute, running().totalMillis)
        assertEquals(nowMs + 30 * minute, running().deadlineAtMillis)
        assertPickerGone()
        rule.assertFocused("Sleep timer")
        assertEquals(listOf("Sleep timer", "Sleep · 30 min"), rule.focusedTexts())
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun theCaptionCountsDownWithoutAnyKeyPress() {
        show()
        openPicker()
        rule.pressLeft()
        rule.pressOk()
        rule.assertFocused("Sleep timer")

        nowMs += 12 * minute
        rule.mainClock.advanceTimeBy(16_000)
        rule.waitForIdle()

        assertEquals(listOf("Sleep timer", "Sleep · 18 min"), rule.focusedTexts())
    }

    @Test
    fun whileRunningThePickerAlsoOffersExtendAndCancel() {
        show()
        timer.start(60)
        rule.waitForIdle()
        openPicker()

        rule.assertFocused("45")
        rule.onNode(focusableWithText("+15 min")).assertExists()
        rule.onNode(focusableWithText("Cancel")).assertExists()
    }

    @Test
    fun extendAddsFifteenMinutesAndCollapsesThePicker() {
        show()
        timer.start(30)
        rule.waitForIdle()
        openPicker()
        rule.focus("+15 min")
        sound.clear()

        rule.pressOk()

        assertEquals(45 * minute, running().totalMillis)
        assertEquals(nowMs + 45 * minute, running().deadlineAtMillis)
        assertPickerGone()
        rule.assertFocused("Sleep timer")
        assertEquals(listOf("Sleep timer", "Sleep · 45 min"), rule.focusedTexts())
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun cancelStopsTheTimer() {
        show()
        timer.start(30)
        rule.waitForIdle()
        openPicker()
        rule.focus("Cancel")

        rule.pressOk()

        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertPickerGone()
        assertEquals(listOf("Sleep timer", "Off"), rule.focusedTexts())
    }

    @Test
    fun offStopsARunningTimerToo() {
        show()
        timer.start(30)
        rule.waitForIdle()
        openPicker()
        rule.focus("Off")

        rule.pressOk()

        assertEquals(SleepTimerState.Idle, timer.state.value)
        rule.assertFocused("Sleep timer")
    }

    @Test
    fun backClosesTheOpenPickerFirstAndStaysOnHome() {
        show()
        openPicker()
        rule.assertFocused("45")
        sound.clear()

        rule.pressBack()

        assertPickerGone()
        rule.assertFocused("Sleep timer")
        rule.onNode(focusableWithText("Wi-Fi")).assertExists()
        assertEquals(1, sound.backCount)
        assertEquals(SleepTimerState.Idle, timer.state.value)
    }

    @Test
    fun theTileTogglesThePickerShut() {
        show()
        openPicker()
        rule.focus("Sleep timer")

        rule.pressOk()

        assertPickerGone()
        rule.assertFocused("Sleep timer")
    }

    @Test
    fun thePickerClosesByItselfWhenTheTimerFires() {
        show()
        timer.start(15)
        rule.waitForIdle()
        openPicker()
        rule.onNodeWithText("Cancel").assertExists()

        timer.cancel() // what the screen sees when the timer ends: not Running any more
        rule.waitForIdle()

        assertPickerGone()
        rule.assertFocused("Sleep timer")
        assertEquals(listOf("Sleep timer", "Off"), rule.focusedTexts())
    }

    @Test
    fun theSettingsDefaultLengthChangesWhatThePickerFocuses() {
        show()
        route = "settings"
        goToSettingsChips()
        repeat(3) { rule.pressLeft() }
        rule.assertFocused("45")
        rule.pressRight()
        rule.assertFocused("60")
        rule.pressRight()
        rule.assertFocused("90")
        sound.clear()
        rule.pressOk()
        rule.waitForIdle()
        assertEquals(90, runBlocking { repo.settings.first() }.sleepMinutes)
        assertEquals(1, sound.selectCount)

        rule.pressBack()
        assertEquals("home", route)
        openPicker()

        rule.assertFocused("90")
    }

    @Test
    fun rightOnTheLastSettingsChipPlaysTheEdgeAndStays() {
        show()
        route = "settings"
        goToSettingsChips()
        rule.assertFocused("120")
        sound.clear()

        rule.pressRight()

        assertEquals(listOf("120"), rule.focusedTexts())
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun exactlyOneElementHasFocusThroughoutTheFlow() {
        show()
        rule.focus("Wi-Fi")
        rule.assertSingleFocus()
        rule.pressDown(); rule.assertSingleFocus()
        rule.pressOk(); rule.assertSingleFocus()
        rule.pressRight(); rule.assertSingleFocus()
        rule.pressLeft(); rule.pressLeft(); rule.assertSingleFocus()
        rule.pressOk(); rule.assertSingleFocus()
        assertTrue(timer.state.value is SleepTimerState.Running)
    }
}

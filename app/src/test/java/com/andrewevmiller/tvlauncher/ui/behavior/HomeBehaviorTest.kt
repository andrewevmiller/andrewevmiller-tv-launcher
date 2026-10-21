package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.HomeDeps
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.home.HomeContent
import com.andrewevmiller.tvlauncher.home.HomeUiState
import com.andrewevmiller.tvlauncher.home.apps.AppDrawerScreen
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.LaunchEntry
import com.andrewevmiller.tvlauncher.home.data.PreviewApps
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.Screensaver
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home as a whole: the real `HomeContent` (feature band, chips, Apps grid, Recent, screensaver tiles, system
 * tiles, header) inside the real `HomeScreen` frame, with fake dependencies and a recording sound. This is the
 * "can I walk from the first app to the bottom and back with the remote" check of BUILD_PLAN section 10.
 *
 * Top to bottom, with the preview apps and two launch-history entries:
 *
 *     Home                       [SETTINGS]  2026-10-21 · 18:05
 *     feature band               "Open →"
 *     chips                      All Video Music Games Tools
 *     Apps                       YouTube Netflix Plex Spotify / Stadia Notes Files All apps
 *     Recent                     Show recent
 *     Screensaver                Google Photos · slideshow, Oxyfield · dot field
 *     Inputs & system            Wi-Fi Search Settings   (no TV inputs under Robolectric)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class HomeBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private val fx = RepoFixture(PreviewApps)

    private val plex = fx.app("Plex")
    private val spotify = fx.app("Spotify")
    private val history = listOf(
        LaunchEntry(plex.key(), plex.label, plex.category, atMillis = 1_792_000_000_000L),
        LaunchEntry(spotify.key(), spotify.label, spotify.category, atMillis = 1_791_990_000_000L),
    )

    private class RecordingScreensaver(override val canSwitch: Boolean = true) : ScreensaverController {
        val applied = mutableListOf<Screensaver>()
        var started = 0
        override fun apply(choice: Screensaver) { applied += choice }
        override fun startNow(): Boolean { started++; return true }
    }

    private fun deps(
        history: List<LaunchEntry> = this.history,
        screensaver: ScreensaverController = NoOpScreensaverController,
        settings: SettingsRepository = FakeSettingsRepository(),
    ): HomeDeps = FakeHomeDeps(fx, history, screensaver, settings)

    private val ChipLabels = setOf("All", "Video", "Music", "Games", "Tools")

    private var drawerOpened = 0
    private var settingsOpened = 0

    private fun showHome(
        deps: HomeDeps = deps(),
        ui: HomeUiState = HomeUiState(),
    ): HomeUiState {
        rule.setContentWithSound(sound) {
            HomeContent(
                deps = deps,
                ui = ui,
                settings = LauncherSettings(),
                onOpenDrawer = { drawerOpened++ },
                onOpenSettings = { settingsOpened++ },
            )
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("YouTube").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        return ui
    }

    /** Files, Down to Recent, Down to the screensaver row, Right to the Oxyfield tile. */
    private fun goToOxyfieldTile() {
        rule.focus("Files")
        rule.pressDown()
        rule.pressDown()
        rule.assertFocused("Google Photos · slideshow")
        rule.pressRight()
        rule.assertFocused("Oxyfield · dot field")
    }

    // -----------------------------------------------------------------------------------------------------
    // Arrival
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun focusLandsOnTheFirstAppTileOnItsOwn() {
        showHome()

        rule.assertFocused("YouTube")
    }

    @Test
    fun comingBackFromTheDrawerFocusesTheAllAppsTile() {
        val ui = HomeUiState().apply { focusAllApps = true }
        showHome(ui = ui)

        rule.assertFocused("All apps")
    }

    @Test
    fun aFreshHomeStateHasRecentCollapsedToASingleShowRecentChip() {
        val ui = showHome()
        assertFalse(ui.recentExpanded)

        // The Recent row sits below the fold; reach it the way the owner would, with the remote.
        rule.focus("Files")
        rule.pressDown()

        rule.assertFocused("Show recent")
        rule.onNodeWithText("Hide").assertDoesNotExist()
        rule.onNodeWithText("RECENT · 2 HIDDEN").assertExists()
        // Collapsed means no entries: Spotify is only in the Apps grid (tile) and not in the Recent timeline.
        assertEquals(1, rule.onAllNodesWithText("Spotify").fetchSemanticsNodes().size)
    }

    @Test
    fun theFeatureBandShowsTheLastOpenedAppFromTheLaunchHistory() {
        showHome()

        rule.onNodeWithText("Open →").assertExists()
        rule.onNodeWithText("Pick up where you left off.").assertExists()
    }

    @Test
    fun withNoHistoryTheFeatureBandWelcomesAndTheRecentRowIsAbsent() {
        showHome(deps = deps(history = emptyList()))

        rule.onNodeWithText("Browse apps →").assertExists()
        rule.onNodeWithText("Show recent").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------------------------------------
    // Walking up
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun upFromTheFirstAppRowReachesAChipThenTheFeatureBandCta() {
        showHome()
        rule.assertFocused("YouTube")

        rule.pressUp()
        rule.assertSingleFocus()
        // The chips row. Focus lands on the chip geometrically nearest the tile (Video for the first column),
        // not necessarily the chip with the same index.
        assertTrue(rule.focusedTexts().toString(), rule.focusedTexts().single() in ChipLabels)

        rule.pressUp()
        rule.assertFocused("Open →")
    }

    @Test
    fun upFromTheFeatureBandReachesTheSettingsChipInTheHeader() {
        showHome()
        rule.pressUp()
        rule.pressUp()
        rule.assertFocused("Open →")

        rule.pressUp()

        rule.assertFocused("SETTINGS")
    }

    @Test
    fun upAtTheVeryTopPlaysEdgeAndKeepsFocus() {
        showHome()
        rule.pressRepeatedly(Key.DirectionUp, 3)
        rule.assertFocused("SETTINGS")
        sound.clear()

        rule.pressUp()

        rule.assertFocused("SETTINGS")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun holdingUpAtTheVeryTopPlaysEdgeOnlyOnce() {
        showHome()
        rule.pressRepeatedly(Key.DirectionUp, 3)
        rule.assertFocused("SETTINGS")
        sound.clear()

        rule.hold(Key.DirectionUp)

        rule.assertFocused("SETTINGS")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun theSettingsChipOpensSettingsOnEnter() {
        showHome()
        rule.pressRepeatedly(Key.DirectionUp, 3)
        rule.assertFocused("SETTINGS")
        sound.clear()

        rule.pressEnter()

        assertEquals(1, settingsOpened)
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun theSettingsChipOpensSettingsOnOk() {
        showHome()
        rule.pressRepeatedly(Key.DirectionUp, 3)

        rule.pressOk()

        assertEquals(1, settingsOpened)
    }

    @Test
    fun downFromTheSettingsChipComesBackDownTheFeatureBand() {
        showHome()
        rule.pressRepeatedly(Key.DirectionUp, 3)
        rule.assertFocused("SETTINGS")

        rule.pressDown()

        rule.assertFocused("Open →")
    }

    // -----------------------------------------------------------------------------------------------------
    // Walking down
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun downFromTheGridsLastRowReachesTheRecentToggleThenScreensaversThenTheSystemRow() {
        showHome()
        rule.focus("Files") // last grid row (row 1, column 1)
        rule.assertSingleFocus()

        rule.pressDown()
        rule.assertFocused("Show recent")

        rule.pressDown()
        rule.assertFocused("Google Photos · slideshow")

        rule.pressDown()
        // The system row: Wi-Fi, Search, Settings (no TV inputs under Robolectric).
        rule.assertSingleFocus()
        assertTrue(
            "expected a system tile, got ${rule.focusedTexts()}",
            rule.focusedTexts().any { it in setOf("Wi-Fi", "Search", "Settings") },
        )
    }

    @Test
    fun downAtTheVeryBottomPlaysEdgeAndKeepsFocus() {
        showHome()
        rule.focus("Files")
        rule.pressRepeatedly(Key.DirectionDown, 3)
        val bottom = rule.focusedTexts()
        sound.clear()

        rule.pressDown()

        assertEquals(bottom, rule.focusedTexts())
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun walkingFromTheTopOfHomeToTheBottomNeverLosesFocus() {
        showHome()
        rule.pressRepeatedly(Key.DirectionUp, 3)
        rule.assertFocused("SETTINGS")
        val visited = mutableListOf<List<String>>()

        repeat(12) {
            rule.pressDown()
            rule.assertSingleFocus()
            visited += rule.focusedTexts()
        }

        // SETTINGS -> CTA -> chip -> YouTube/grid rows -> Recent -> screensaver -> system, ending on an edge.
        assertTrue("passed the CTA: $visited", visited.any { "Open →" in it })
        assertTrue("passed the Apps grid: $visited", visited.any { "YouTube" in it })
        assertTrue("reached Recent: $visited", visited.any { "Show recent" in it })
        assertTrue("reached the screensavers: $visited", visited.any { "Google Photos · slideshow" in it })
        assertTrue("reached the system row: $visited", visited.any { it.any { t -> t in setOf("Wi-Fi", "Search", "Settings") } })
    }

    @Test
    fun walkingDownTheWholeHomeNeverLowersTheFocusTickPitch() {
        showHome()
        rule.pressRepeatedly(Key.DirectionUp, 3)
        rule.assertFocused("SETTINGS")
        sound.clear()

        rule.pressRepeatedly(Key.DirectionDown, 10)

        val ticks = sound.ticks
        assertTrue("one tick per gained focus, got $ticks", ticks.size >= 5)
        assertTrue("pitch rises (or holds) going down: $ticks", ticks.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("the bottom row is higher than the top: $ticks", ticks.last() > ticks.first())
    }

    @Test
    fun walkingUpFromTheSystemRowRetracesTheSameSections() {
        showHome()
        rule.focus("Files")
        rule.pressRepeatedly(Key.DirectionDown, 3)

        rule.pressUp()
        rule.assertFocused("Google Photos · slideshow")
        rule.pressUp()
        rule.assertFocused("Show recent")
        rule.pressUp()
        // Back into the grid's last row.
        rule.assertSingleFocus()
        assertTrue(rule.focusedTexts().toString(), rule.focusedTexts().any { it in setOf("Stadia Notes", "Files", "All apps") })
    }

    // -----------------------------------------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun okOnTheFirstAppLaunchesItWithItsMotifOnce() {
        showHome()
        sound.clear()

        rule.pressOk()

        assertEquals(listOf(fx.app("YouTube")), sound.launches)
        assertEquals(listOf(fx.app("YouTube")), fx.launched.toList())
    }

    @Test
    fun okOnAllAppsOpensTheDrawer() {
        showHome(ui = HomeUiState().apply { focusAllApps = true })
        rule.assertFocused("All apps")

        rule.pressOk()

        assertEquals(1, drawerOpened)
    }

    @Test
    fun theFeatureBandCtaLaunchesTheLastOpenedAppWithoutAnExtraSelectSound() {
        showHome()
        rule.pressUp()
        rule.pressUp()
        rule.assertFocused("Open →")
        sound.clear()

        rule.pressOk()

        assertEquals(listOf(plex), sound.launches)
        assertEquals(listOf(plex), fx.launched.toList())
        assertEquals(0, sound.selectCount)
    }

    @Test
    fun withNoHistoryTheBandCtaOpensTheDrawer() {
        showHome(deps = deps(history = emptyList()))
        rule.pressUp()
        rule.pressUp()
        rule.assertFocused("Browse apps →")

        rule.pressOk()

        assertEquals(1, drawerOpened)
        assertEquals(emptyList<Any>(), fx.launched.toList())
    }

    @Test
    fun aChipFiltersTheGridInPlaceAndKeepsFocusOnTheChip() {
        showHome()
        rule.pressUp()
        rule.focus("Video")

        rule.pressOk()

        rule.assertFocused("Video")
        rule.onNodeWithText("Spotify").assertDoesNotExist()
        rule.onNode(focusableWithText("Plex")).assertExists()
        rule.onNodeWithText("All apps").assertExists()
    }

    @Test
    fun theRecentRowExpandsOnOkAndAnEntryRelaunchesTheApp() {
        showHome()
        rule.focus("Files")
        rule.pressDown()
        rule.assertFocused("Show recent")
        rule.pressOk()
        rule.assertFocused("Hide")
        rule.onNodeWithText("RECENT").assertExists()
        sound.clear()

        rule.pressRight()
        // The newest entry, not the Plex tile in the grid: the timeline entry also shows its "Video" subtitle.
        assertTrue(rule.focusedTexts().toString(), rule.focusedTexts().containsAll(listOf("Plex", "Video")))
        rule.pressOk()

        assertEquals(listOf(plex), sound.launches)
        assertEquals(listOf(plex), fx.launched.toList())
        assertEquals("the entry plays its motif, not select", 0, sound.selectCount)
    }

    @Test
    fun theOxyfieldScreensaverTileStartsItAndPlaysTheSleepSound() {
        val screensaver = RecordingScreensaver()
        val settings = FakeSettingsRepository(LauncherSettings(screensaver = Screensaver.GOOGLE_PHOTOS))
        showHome(deps = deps(screensaver = screensaver, settings = settings))
        goToOxyfieldTile()
        sound.clear()

        rule.pressOk()
        rule.waitForIdle()

        assertEquals(1, sound.sleepCount)
        assertEquals(listOf(Screensaver.OXYFIELD), screensaver.applied)
        assertEquals(1, screensaver.started)
        assertEquals(Screensaver.OXYFIELD, runBlocking { settings.settings.first() }.screensaver)
    }

    @Test
    fun whenTheTvRefusesAnOnDemandStartAMessageIsSetForTheToast() {
        val ui = showHome() // NoOpScreensaverController.startNow() is false
        goToOxyfieldTile()

        rule.pressOk()
        rule.waitForIdle()

        assertEquals("This TV won't start it on demand. It will run when idle.", ui.message)
        rule.assertSingleFocus()
    }

    @Test
    fun editModeWorksInsideTheHomeFrameAndPersistsTheMove() {
        showHome()
        rule.assertFocused("YouTube")

        rule.longPressOk()
        rule.pressRight()
        rule.pressOk() // drop
        rule.waitForIdle()

        assertEquals(
            listOf("Netflix", "YouTube", "Plex", "Spotify", "Stadia Notes", "Files"),
            fx.pinnedLabels(),
        )
        assertEquals("a long press and a drop never launch", emptyList<Any>(), fx.launched.toList())
    }

    @Test
    fun backCancelsEditModeInsideTheHomeFrame() {
        showHome()
        val before = fx.pinnedLabels()
        rule.longPressOk()
        rule.pressRight()

        rule.pressBack()
        rule.waitForIdle()

        assertEquals(before, fx.pinnedLabels())
        rule.assertFocused("YouTube")
    }

    @Test
    fun theDrawerRoundTripReturnsToHomeWithFocusOnAllApps() {
        // The routing of FieldRoot in miniature: Home <-> Drawer, with the same ui.focusAllApps hand-off.
        var inDrawer by mutableStateOf(false)
        val ui = HomeUiState()
        rule.setContentWithSound(sound) {
            if (inDrawer) {
                AppDrawerScreen(
                    repository = fx.repo,
                    onBack = {
                        ui.focusAllApps = true
                        inDrawer = false
                    },
                )
            } else {
                HomeContent(
                    deps = deps(),
                    ui = ui,
                    settings = LauncherSettings(),
                    onOpenDrawer = { inDrawer = true },
                    onOpenSettings = {},
                )
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("YouTube").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.focus("All apps")
        rule.pressOk()
        rule.waitForIdle()

        rule.onNodeWithText("EVERY APP · A–Z · 6").assertExists()
        rule.assertFocused("Files") // the drawer is A to Z and focuses its first tile

        rule.pressBack()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("All apps").fetchSemanticsNodes().isNotEmpty() && !inDrawer
        }
        rule.waitForIdle()

        rule.assertFocused("All apps")
    }

    @Test
    fun filterCategoriesAreWiredToTheChips() {
        val ui = showHome()
        assertEquals(null, ui.category)
        rule.pressUp()
        rule.focus("Music")

        rule.pressOk()

        assertEquals(AppCategory.MUSIC, ui.category)
    }
}

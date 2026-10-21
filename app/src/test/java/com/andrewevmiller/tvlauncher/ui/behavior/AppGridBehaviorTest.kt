package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import com.andrewevmiller.tvlauncher.home.apps.AppGridState
import com.andrewevmiller.tvlauncher.home.apps.AppsSection
import com.andrewevmiller.tvlauncher.home.apps.rememberAppGridState
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.PreviewApps
import com.andrewevmiller.tvlauncher.sound.LocalSoundRow
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUILD_PLAN section 5.4, the Home app grid: the real `AppsSection` (so `AppGridSection`, `AppTile`, `gridMove`
 * and the focus-survival plumbing) driven by D-pad key events on the JVM.
 *
 * With the six preview apps pinned, the grid has 7 cells in 4 columns:
 *
 *     YouTube        Netflix   Plex    Spotify
 *     Stadia Notes   Files     All apps
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class AppGridBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()

    private var category by mutableStateOf<AppCategory?>(null)
    private var drawerOpened = 0
    private var exitedUp = 0
    private var exitedDown = 0
    private lateinit var state: AppGridState

    /**
     * Shows the Apps section for [fx]. [above] and [below] add a focusable stand-in for the chips row and the
     * next section. [exitCallbacks] wires `onExitUp` / `onExitDown` to counters, like HomeContent could.
     */
    private fun show(
        fx: RepoFixture = RepoFixture(PreviewApps),
        above: Boolean = false,
        below: Boolean = false,
        exitCallbacks: Boolean = false,
        soundRowBase: Int = 0,
    ): RepoFixture {
        rule.setContentWithSound(sound) {
            state = rememberAppGridState()
            CompositionLocalProvider(LocalSoundRow provides soundRowBase) {
                Column {
                    if (above) FocusableStub("Above chip")
                    AppsSection(
                        repository = fx.repo,
                        category = category,
                        onOpenDrawer = { drawerOpened++ },
                        state = state,
                        onExitUp = if (exitCallbacks) ({ exitedUp++ }) else null,
                        onExitDown = if (exitCallbacks) ({ exitedDown++ }) else null,
                    )
                    if (below) FocusableStub("Below chip")
                }
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("All apps").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        return fx
    }

    /** Puts focus on cell [index] through the grid's own entry point, then forgets the sounds that made. */
    private fun focusCell(index: Int) {
        rule.runOnIdle { check(state.requestFocus(index)) { "cell $index is not on screen" } }
        rule.waitForIdle()
        rule.assertSingleFocus()
        sound.clear()
    }

    private fun bounds(label: String): DpRect = rule.onNodeWithText(label).getBoundsInRoot()

    private fun assertSameRow(vararg labels: String) {
        val tops = labels.map { bounds(it).top.value }
        assertTrue("$labels share a row, tops=$tops", tops.all { abs(it - tops.first()) < 1f })
    }

    private fun assertSameColumn(vararg labels: String) {
        val lefts = labels.map { bounds(it).left.value }
        assertTrue("$labels share a column, lefts=$lefts", lefts.all { abs(it - lefts.first()) < 1f })
    }

    // -----------------------------------------------------------------------------------------------------
    // Geometry: 6 pinned + "All apps"
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun sixPinnedAppsPlusAllAppsAreSevenCellsInRowsOfFourAndThree() {
        show()

        listOf("YouTube", "Netflix", "Plex", "Spotify", "Stadia Notes", "Files", "All apps").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        assertSameRow("YouTube", "Netflix", "Plex", "Spotify")
        assertSameRow("Stadia Notes", "Files", "All apps")
        assertTrue(bounds("Stadia Notes").top.value > bounds("YouTube").top.value + 100f)
        // Columns line up: the short last row starts at column 0 and is left aligned.
        assertSameColumn("YouTube", "Stadia Notes")
        assertSameColumn("Netflix", "Files")
        assertSameColumn("Plex", "All apps")
        // Four 192dp tiles, 32dp gaps.
        assertEquals(192f, bounds("YouTube").let { it.right.value - it.left.value }, 1f)
        assertEquals(224f, bounds("Netflix").left.value - bounds("YouTube").left.value, 1f)
    }

    @Test
    fun ninePinnedAppsPlusAllAppsAreTenCellsInRowsOfFourFourAndTwo() {
        val fx = RepoFixture(numberedApps(12), pinnedCount = 9)
        show(fx)

        (1..9).forEach { rule.onNodeWithText("App %02d".format(it)).assertExists() }
        rule.onNodeWithText("App 10").assertDoesNotExist()
        assertSameRow("App 01", "App 02", "App 03", "App 04")
        assertSameRow("App 05", "App 06", "App 07", "App 08")
        assertSameRow("App 09", "All apps")
        assertSameColumn("App 01", "App 05", "App 09")
        assertSameColumn("App 02", "App 06", "All apps")
        assertTrue(bounds("App 09").top.value > bounds("App 05").top.value)
    }

    // -----------------------------------------------------------------------------------------------------
    // Left / right: stay in the row, never wrap, edge() at the ends
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun rightMovesAlongTheRowOneTileAtATime() {
        show()
        focusCell(0)

        rule.pressRight()
        rule.assertFocused("Netflix")
        rule.pressRight()
        rule.assertFocused("Plex")
        rule.pressRight()
        rule.assertFocused("Spotify")
        assertEquals(0, sound.edgeCount)
    }

    @Test
    fun rightAtTheEndOfARowDoesNotWrapAndPlaysEdgeOnce() {
        show()
        focusCell(3) // Spotify, last tile of row 0

        rule.pressRight()

        rule.assertFocused("Spotify")
        assertEquals("exactly one edge per press", 1, sound.edgeCount)
        assertEquals("no focus gained, so no tick", emptyList<Int>(), sound.ticks)
    }

    @Test
    fun everyEdgePressPlaysEdgeExactlyOnceWithNoFocusMove() {
        show()
        focusCell(3)

        repeat(3) { rule.pressRight() }

        rule.assertFocused("Spotify")
        assertEquals(3, sound.edgeCount)
    }

    @Test
    fun holdingRightAtTheRowEndPlaysEdgeOnceForTheWholeHoldNotOncePerRepeat() {
        show()
        focusCell(3) // Spotify, last tile of row 0

        rule.hold(Key.DirectionRight) // one key down plus several auto-repeats

        rule.assertFocused("Spotify")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun holdingRightTravelsAlongTheRowAndKeyRepeatNeverPlaysEdge() {
        show()
        focusCell(0)

        rule.hold(Key.DirectionRight) // down + repeats: more than the three steps the row has

        rule.assertFocused("Spotify")
        assertEquals("a repeat that runs into the row end stays silent", 0, sound.edgeCount)
        assertEquals("one tick per tile entered", listOf(0, 0, 0), sound.ticks)
    }

    @Test
    fun leftAtColumnZeroIsAnEdgeAndDoesNotWrapToThePreviousRow() {
        show()
        focusCell(4) // Stadia Notes, column 0 of row 1

        rule.pressLeft()

        rule.assertFocused("Stadia Notes")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun leftOnTheVeryFirstTileIsAnEdge() {
        show()
        focusCell(0)

        rule.pressLeft()

        rule.assertFocused("YouTube")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun rightAtTheEndOfTheShortLastRowIsAnEdge() {
        show()
        focusCell(6) // "All apps", the last cell of a three-cell row

        rule.pressRight()

        rule.assertFocused("All apps")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun rightFromTheLastTileOfARowNeverJumpsToTheNextRow() {
        show()
        focusCell(3)

        rule.pressRight()

        // Stadia Notes is the next cell in reading order; focus must not wrap onto it.
        rule.onNodeWithText("Stadia Notes").assertIsNotFocused()
    }

    // -----------------------------------------------------------------------------------------------------
    // Up / down: keep the column, short last row lands on its last tile
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun downKeepsTheColumn() {
        show()
        focusCell(1) // Netflix, column 1

        rule.pressDown()

        rule.assertFocused("Files")
        assertEquals(0, sound.edgeCount)
    }

    @Test
    fun downFromColumnZeroLandsOnColumnZeroOfTheNextRow() {
        show()
        focusCell(0)

        rule.pressDown()

        rule.assertFocused("Stadia Notes")
    }

    @Test
    fun downFromColumnThreeIntoTheThreeCellLastRowLandsOnItsLastTile() {
        show()
        focusCell(3) // Spotify, column 3; the last row only has columns 0..2

        rule.pressDown()

        rule.assertFocused("All apps")
    }

    @Test
    fun upReturnsToTheSameColumn() {
        show()
        focusCell(1)
        rule.pressDown()
        rule.assertFocused("Files")

        rule.pressUp()

        rule.assertFocused("Netflix")
    }

    @Test
    fun upFromTheClampedLastTileGoesToTheTileAboveItNotTheOriginalColumn() {
        show()
        focusCell(3)
        rule.pressDown()
        rule.assertFocused("All apps")

        rule.pressUp()

        // "All apps" sits in column 2, so Up lands on Plex (column 2), as gridMove defines.
        rule.assertFocused("Plex")
    }

    @Test
    fun nineAppCaseRowsOfFourFourAndTwoFollowTheSameColumnRules() {
        val fx = RepoFixture(numberedApps(12), pinnedCount = 9)
        show(fx)
        focusCell(3) // App 04, column 3 of row 0

        rule.pressDown()
        rule.assertFocused("App 08")
        rule.pressDown() // row 2 only has columns 0..1: lands on its last tile
        rule.assertFocused("All apps")
        rule.pressUp()
        rule.assertFocused("App 06")
        rule.pressLeft()
        rule.assertFocused("App 05")
        rule.pressDown()
        rule.assertFocused("App 09")
        rule.pressRight()
        rule.assertFocused("All apps")
        rule.pressRight()
        rule.assertFocused("All apps")
        assertEquals(1, sound.edgeCount)
    }

    // -----------------------------------------------------------------------------------------------------
    // Leaving the grid vertically
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun upFromTheFirstRowInvokesOnExitUp() {
        show(exitCallbacks = true)
        focusCell(2)

        rule.pressUp()

        assertEquals(1, exitedUp)
        assertEquals(0, exitedDown)
        assertEquals("the caller moves focus; the grid does not play edge", 0, sound.edgeCount)
    }

    @Test
    fun upFromTheFirstRowMovesFocusToTheSectionAboveWhenOnExitUpIsNull() {
        show(above = true)
        focusCell(0)

        rule.pressUp()

        rule.assertFocused("Above chip")
        assertEquals(0, sound.edgeCount)
    }

    @Test
    fun upFromTheFirstRowWithNothingAbovePlaysEdgeOnce() {
        show()
        focusCell(0)

        rule.pressUp()

        rule.assertFocused("YouTube")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun downFromTheLastRowInvokesOnExitDown() {
        show(exitCallbacks = true)
        focusCell(5) // Files, last row

        rule.pressDown()

        assertEquals(1, exitedDown)
        assertEquals(0, exitedUp)
        assertEquals(0, sound.edgeCount)
    }

    @Test
    fun downFromTheLastRowMovesFocusToTheNextSectionWhenOnExitDownIsNull() {
        show(below = true)
        focusCell(4)

        rule.pressDown()

        rule.assertFocused("Below chip")
        assertEquals(0, sound.edgeCount)
    }

    @Test
    fun downFromTheLastRowWithNothingBelowPlaysEdgeOnce() {
        show()
        focusCell(6)

        rule.pressDown()

        rule.assertFocused("All apps")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun onlyTheFirstRowExitsUpAndOnlyTheLastRowExitsDown() {
        show(exitCallbacks = true)
        focusCell(4) // row 1 is the last row, so Up is an ordinary move

        rule.pressUp()

        assertEquals(0, exitedUp)
        rule.assertFocused("YouTube")
        rule.pressDown()
        rule.assertFocused("Stadia Notes")
        assertEquals(0, exitedDown)
    }

    // -----------------------------------------------------------------------------------------------------
    // Focus tick: pitched by the visual row
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun focusTickGainedOnTheFirstRowPlaysRowZero() {
        show()

        rule.runOnIdle { state.requestFocus(0) }
        rule.waitForIdle()

        assertEquals(listOf(0), sound.ticks)
    }

    @Test
    fun focusTickUsesTheVisualRowSoItRisesWhenMovingDownAGridRow() {
        show()
        focusCell(0)

        rule.pressRight() // still row 0
        rule.pressDown() // row 1
        rule.pressRight() // row 1
        rule.pressUp() // back to row 0

        assertEquals(listOf(0, 1, 1, 0), sound.ticks)
    }

    @Test
    fun focusTickAddsTheGridRowToTheRowTheSectionSitsIn() {
        show(soundRowBase = 3) // HomeScreen provides the section's own row index
        focusCell(0)

        rule.pressDown()
        rule.pressRight()

        assertEquals(listOf(4, 4), sound.ticks)
    }

    @Test
    fun everyGainedFocusPlaysExactlyOneTick() {
        show()
        focusCell(0)

        rule.pressRight()
        rule.pressRight()
        rule.pressRight()
        rule.pressRight() // edge: no focus change, no tick

        assertEquals(3, sound.ticks.size)
        assertEquals(1, sound.edgeCount)
    }

    // -----------------------------------------------------------------------------------------------------
    // Enter
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun okOnAnAppTilePlaysLaunchOnceAndLaunchesThroughTheRepositoryOnce() {
        val fx = show()
        focusCell(2) // Plex

        rule.pressOk()

        val plex = fx.app("Plex")
        assertEquals(listOf(plex), sound.launches)
        assertEquals(listOf(plex), fx.launched.toList())
        assertEquals("launch is the voiced sound, not select", 0, sound.selectCount)
        assertEquals(0, drawerOpened)
    }

    @Test
    fun enterKeyOnAnAppTileLaunchesToo() {
        val fx = show()
        focusCell(0)

        rule.pressEnter()

        assertEquals(listOf(fx.app("YouTube")), sound.launches)
        assertEquals(listOf(fx.app("YouTube")), fx.launched.toList())
    }

    @Test
    fun okOnAnAppTileLaunchesAgainOnEachPress() {
        val fx = show()
        focusCell(1)

        rule.pressOk()
        rule.pressOk()

        assertEquals(2, fx.launched.size)
        assertEquals(2, sound.launches.size)
    }

    @Test
    fun okOnTheAllAppsTileOpensTheDrawer() {
        val fx = show()
        focusCell(6)

        rule.pressOk()

        assertEquals(1, drawerOpened)
        assertEquals(1, sound.selectCount)
        assertEquals(emptyList<Any>(), sound.launches)
        assertEquals(emptyList<Any>(), fx.launched.toList())
    }

    // -----------------------------------------------------------------------------------------------------
    // Category filter
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun categoryFilterShowsFewerTilesAndKeepsAllApps() {
        show()
        rule.onNodeWithText("Spotify").assertExists()

        rule.runOnUiThread { category = AppCategory.VIDEO }
        rule.waitForIdle()

        listOf("YouTube", "Netflix", "Plex", "All apps").forEach { rule.onNodeWithText(it).assertExists() }
        listOf("Spotify", "Stadia Notes", "Files").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        assertSameRow("YouTube", "Netflix", "Plex", "All apps")
    }

    @Test
    fun categoryFilterWithNoMatchesLeavesOnlyTheAllAppsTile() {
        show()

        rule.runOnUiThread { category = AppCategory.OTHER }
        rule.waitForIdle()

        rule.onNodeWithText("All apps").assertExists()
        listOf("YouTube", "Spotify", "Files").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun filteredGridFollowsTheSameRules() {
        show()
        rule.runOnUiThread { category = AppCategory.VIDEO }
        rule.waitForIdle()
        focusCell(0)

        rule.pressRight()
        rule.pressRight()
        rule.pressRight()
        rule.assertFocused("All apps")
        rule.pressRight()
        rule.assertFocused("All apps")
        assertEquals(1, sound.edgeCount)
    }

    // -----------------------------------------------------------------------------------------------------
    // Focus survival
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun focusSurvivesUnpinningTheFocusedAppAndLandsOnThePreviousIndex() {
        val fx = show()
        focusCell(2) // Plex

        fx.unpin("Plex")
        rule.waitForIdle()

        rule.onNodeWithText("Plex").assertDoesNotExist()
        // The tile now at index 2 is Spotify.
        rule.assertFocused("Spotify")
    }

    @Test
    fun focusSurvivesUnpinningTheOnlyAppByFallingToTheAllAppsTile() {
        val fx = RepoFixture(PreviewApps, pinnedCount = 1)
        show(fx)
        focusCell(0) // YouTube, the only pinned app

        fx.unpin("YouTube")
        rule.waitForIdle()

        rule.onNodeWithText("YouTube").assertDoesNotExist()
        rule.assertFocused("All apps")
    }

    @Test
    fun focusSurvivesHidingTheFocusedApp() {
        val fx = show()
        focusCell(0) // YouTube

        fx.hide("YouTube")
        rule.waitForIdle()

        rule.onNodeWithText("YouTube").assertDoesNotExist()
        rule.assertFocused("Netflix")
    }

    @Test
    fun focusIsClampedToTheLastCellWhenTheListShrinksPastTheFocusedIndex() {
        val fx = show()
        focusCell(6) // "All apps", index 6 of 7

        fx.unpin("Files")
        fx.unpin("Stadia Notes")
        rule.waitForIdle()

        // Four apps left, so the cells are 0..4 and the old index 6 clamps to the last cell, "All apps".
        rule.onNodeWithText("Files").assertDoesNotExist()
        rule.assertFocused("All apps")
    }

    @Test
    fun focusOnTheLastAppFallsToTheCellNowAtThatIndex() {
        val fx = show()
        focusCell(5) // Files, the last app

        fx.unpin("Files")
        rule.waitForIdle()

        rule.assertFocused("All apps")
    }

    @Test
    fun focusSurvivesAPinAddedWhileFocusedAndStaysOnTheSameIndex() {
        val fx = RepoFixture(PreviewApps, pinnedCount = 3)
        show(fx)
        focusCell(1) // Netflix

        fx.pin("Files")
        rule.waitForIdle()

        rule.onNodeWithText("Files").assertExists()
        rule.assertFocused("Netflix")
    }

    @Test
    fun focusSurvivesAFilterChangeWhileTheGridHasFocus() {
        show()
        focusCell(1) // Netflix

        rule.runOnUiThread { category = AppCategory.GAMES } // only "Stadia Notes" + All apps remain
        rule.waitForIdle()

        // Index 1 of the filtered grid is the All apps cell.
        rule.assertFocused("All apps")
    }

    @Test
    fun theGridDoesNotStealFocusFromAnotherSectionWhenTheListChanges() {
        val fx = show(above = true)
        rule.focus("Above chip")
        rule.assertFocused("Above chip")
        sound.clear()

        fx.unpin("Plex")
        fx.hide("Files")
        rule.waitForIdle()
        rule.runOnUiThread { category = AppCategory.MUSIC }
        rule.waitForIdle()

        rule.assertFocused("Above chip")
        assertEquals("no focus was gained anywhere else", emptyList<Int>(), sound.ticks)
    }

    @Test
    fun theGridDoesNotTakeFocusOnFirstCompositionByItself() {
        show()

        rule.assertNoFocus()
    }

    @Test
    fun focusCanBeSentIntoTheGridFromOutsideWithRequestEntry() {
        show()

        rule.runOnIdle { check(state.requestEntry(fromTop = false, preferredColumn = 3)) }
        rule.waitForIdle()

        // Entering from below on column 3 lands on the last tile of the short last row.
        rule.assertFocused("All apps")
    }
}

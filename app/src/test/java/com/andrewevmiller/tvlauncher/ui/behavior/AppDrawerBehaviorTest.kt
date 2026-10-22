package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.home.HomeCategoryChips
import com.andrewevmiller.tvlauncher.home.apps.AppDrawerScreen
import com.andrewevmiller.tvlauncher.home.apps.AppGridState
import com.andrewevmiller.tvlauncher.home.apps.rememberAppGridState
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.ui.components.ChipsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The full-screen All apps drawer (BUILD_PLAN section 5.4): a `LazyVerticalGrid`, so tiles leave composition and
 * focus moves have to scroll first. The real `AppDrawerScreen` over a [FakeAppsRepository] with 20 apps:
 *
 *     App 01  App 02  App 03  App 04
 *     ...
 *     App 17  App 18  App 19  App 20
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class AppDrawerBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private var backs = 0
    private var category by mutableStateOf<AppCategory?>(null)
    private lateinit var state: AppGridState

    private fun show(
        count: Int = 20,
        pinnedCount: Int = 6,
        withHeader: Boolean = true,
        reducedMotion: Boolean = true,
        realChips: Boolean = false,
    ): RepoFixture {
        val fx = RepoFixture(numberedApps(count), pinnedCount)
        rule.setContentWithSound(sound, reducedMotion = reducedMotion) {
            state = rememberAppGridState()
            AppDrawerScreen(
                repository = fx.repo,
                onBack = { backs++ },
                category = category,
                state = state,
                header = {
                    if (realChips) {
                        // What FieldRoot puts in the drawer's header slot.
                        ChipsRow(options = HomeCategoryChips, selected = category, onSelect = { category = it })
                    } else if (withHeader) {
                        FocusableStub("Header chip")
                    }
                },
            )
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("App 01").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        return fx
    }

    // -----------------------------------------------------------------------------------------------------

    @Test
    fun drawerFocusesTheFirstTileByItself() {
        show()

        rule.assertFocused("App 01")
    }

    @Test
    fun drawerShowsTitleAndCountAndEveryAppUntilItScrolls() {
        show()

        rule.onNodeWithText("All apps").assertExists()
        rule.onNodeWithText("EVERY APP · A–Z · 20").assertExists()
        rule.onNodeWithText("App 01").assertExists()
        rule.onNodeWithText("App 04").assertExists()
        // A lazy grid composes only what is on screen (plus a little prefetch): the last row is not there yet.
        rule.onNodeWithText("App 20").assertDoesNotExist()
    }

    @Test
    fun downRepeatedlyScrollsAndFocusIsNeverLost() {
        show()
        // Rows 0..4: four presses reach the last row. After each press exactly one element has focus.
        listOf("App 05", "App 09", "App 13", "App 17").forEach { expected ->
            rule.pressDown()
            rule.assertSingleFocus()
            rule.assertFocused(expected)
        }
    }

    @Test
    fun downRepeatedlyScrollsAndFocusIsNeverLostWithAnimatedScrolling() {
        show(reducedMotion = false)

        listOf("App 05", "App 09", "App 13", "App 17").forEach { expected ->
            rule.pressDown()
            rule.assertSingleFocus()
            rule.assertFocused(expected)
        }
    }

    @Test
    fun holdingDownScrollsThroughTheRowsAndFocusEndsOnTheLastRow() {
        show()

        rule.hold(Key.DirectionDown) // repeats arrive back to back, while earlier scrolls are still running

        rule.assertSingleFocus()
        rule.assertFocused("App 17")
        assertEquals("a held key that runs out of rows stays silent", 0, sound.edgeCount)
    }

    @Test
    fun holdingDownWithAnimatedScrollingEndsOnTheLastRowToo() {
        show(reducedMotion = false)

        rule.hold(Key.DirectionDown)

        rule.assertSingleFocus()
        rule.assertFocused("App 17")
    }

    @Test
    fun holdingUpFromTheLastRowWalksBackUpToTheFirstRowAndOnIntoTheHeader() {
        show()
        rule.hold(Key.DirectionDown)
        rule.assertFocused("App 17")

        // Four presses reach row 0; the hold sends more, and the next one leaves the grid upwards.
        rule.hold(Key.DirectionUp)

        rule.assertSingleFocus()
        assertTrue(
            "expected the first row or the header, got ${rule.focusedTexts()}",
            rule.focusedTexts().first() in setOf("App 01", "Header chip"),
        )
    }

    @Test
    fun scrollingDownAndBackUpReturnsToTheFirstRowWithFocusIntact() {
        show()

        rule.pressRepeatedly(Key.DirectionDown, 4)
        rule.assertFocused("App 17")
        rule.pressRepeatedly(Key.DirectionUp, 4)

        rule.assertFocused("App 01")
        rule.onNodeWithText("App 20").assertDoesNotExist()
    }

    @Test
    fun downFromTheLastRowPlaysEdgeOnceAndKeepsFocus() {
        show()
        rule.pressRepeatedly(Key.DirectionDown, 4)
        sound.clear()

        rule.pressDown()

        rule.assertFocused("App 17")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun columnIsKeptAcrossScrolling() {
        show()
        rule.pressRight() // column 1
        rule.assertFocused("App 02")

        rule.pressRepeatedly(Key.DirectionDown, 4)

        rule.assertFocused("App 18")
    }

    @Test
    fun shortLastRowLandsOnItsLastTileWhenComingDownFromColumnThree() {
        show(count = 18) // rows of 4,4,4,4,2
        rule.pressRepeatedly(Key.DirectionRight, 3)
        rule.assertFocused("App 04")
        rule.pressRepeatedly(Key.DirectionDown, 3)
        rule.assertFocused("App 16")

        rule.pressDown() // row 4 only has App 17 and App 18

        rule.assertFocused("App 18")
    }

    @Test
    fun upFromTheShortLastRowReturnsToTheTileAboveItsColumn() {
        show(count = 18)
        rule.pressRepeatedly(Key.DirectionDown, 4)
        rule.pressRight()
        rule.assertFocused("App 18")

        rule.pressUp()

        rule.assertFocused("App 14")
    }

    @Test
    fun rightAtTheEndOfARowIsAnEdgeAndLeftAtColumnZeroIsAnEdge() {
        show()
        rule.pressRepeatedly(Key.DirectionRight, 3)
        rule.assertFocused("App 04")
        sound.clear()

        rule.pressRight()
        rule.assertFocused("App 04")
        assertEquals(1, sound.edgeCount)

        rule.pressRepeatedly(Key.DirectionLeft, 3)
        rule.assertFocused("App 01")
        sound.clear()
        rule.pressLeft()
        rule.assertFocused("App 01")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun shortLastRowEndIsAnEdgeToTheRight() {
        show(count = 18)
        rule.pressRepeatedly(Key.DirectionDown, 4)
        rule.pressRight()
        rule.assertFocused("App 18")
        sound.clear()

        rule.pressRight()

        rule.assertFocused("App 18")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun upFromTheFirstRowGoesIntoTheHeaderSlot() {
        show()
        rule.pressRepeatedly(Key.DirectionRight, 2)
        rule.assertFocused("App 03")

        rule.pressUp()

        rule.assertFocused("Header chip")
    }

    @Test
    fun upFromTheFirstRowReachesTheChipsRowInTheHeaderSlot() {
        show(realChips = true)

        rule.pressUp()

        rule.assertSingleFocus()
        assertTrue(
            rule.focusedTexts().toString(),
            rule.focusedTexts().single() in setOf("All", "Video", "Music", "Games", "Tools"),
        )
    }

    @Test
    fun choosingAChipInTheDrawerFiltersTheGridAndFocusStaysOnTheChip() {
        show(realChips = true)
        rule.pressUp()
        rule.focus("Tools")
        rule.assertFocused("Tools")

        rule.pressOk() // App 04, 09, 14, 19
        rule.waitForIdle()

        rule.onNodeWithText("EVERY APP · A–Z · 4").assertExists()
        rule.onNodeWithText("App 01").assertDoesNotExist()
        assertEquals("focus must stay on the chip", listOf("Tools"), rule.focusedTexts())
    }

    @Test
    fun chipsInTheDrawerHeaderCanBeWalkedAndEdgeAtTheEnds() {
        show(realChips = true)
        rule.pressUp()
        rule.focus("All")
        sound.clear()

        rule.pressLeft()
        assertEquals(1, sound.edgeCount)
        rule.pressRight()
        rule.assertFocused("Video")
    }

    @Test
    fun downFromTheHeaderSlotReturnsToTheFirstRow() {
        show()
        rule.pressUp()
        rule.assertFocused("Header chip")

        rule.pressDown()

        rule.assertFocused("App 01")
    }

    @Test
    fun upFromTheFirstRowWithNothingInTheHeaderPlaysEdge() {
        show(withHeader = false)
        sound.clear()

        rule.pressUp()

        rule.assertFocused("App 01")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun backCallsOnBackAndPlaysTheBackSound() {
        show()
        sound.clear()

        rule.pressBack()

        assertEquals(1, backs)
        assertEquals(1, sound.backCount)
    }

    @Test
    fun backStillWorksAfterScrollingDeepIntoTheGrid() {
        show()
        rule.pressRepeatedly(Key.DirectionDown, 4)

        rule.pressBack()

        assertEquals(1, backs)
    }

    @Test
    fun okOnATilePlaysItsLaunchMotifAndLaunchesOnce() {
        val fx = show()
        rule.pressRight()
        sound.clear()

        rule.pressOk()

        assertEquals(listOf(fx.app("App 02")), sound.launches)
        assertEquals(listOf(fx.app("App 02")), fx.launched.toList())
        assertEquals(0, backs)
    }

    @Test
    fun okOnAScrolledToTileLaunchesThatTile() {
        val fx = show()
        rule.pressRepeatedly(Key.DirectionDown, 4)

        rule.pressOk()

        assertEquals(listOf(fx.app("App 17")), fx.launched.toList())
    }

    @Test
    fun focusTickIsPitchedByTheVisualRowOfTheDrawerGrid() {
        show()
        sound.clear()

        rule.pressRight() // row 0
        rule.pressDown() // row 1
        rule.pressDown() // row 2
        rule.pressUp() // row 1

        assertEquals(listOf(0, 1, 2, 1), sound.ticks)
    }

    @Test
    fun categoryFilterShowsOnlyThatCategoryInTheDrawer() {
        show()
        rule.runOnUiThread { category = AppCategory.TOOLS } // App 04, 09, 14, 19
        rule.waitForIdle()

        listOf("App 04", "App 09", "App 14", "App 19").forEach { rule.onNodeWithText(it).assertExists() }
        listOf("App 01", "App 02", "App 03", "App 05").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.onNodeWithText("EVERY APP · A–Z · 4").assertExists()
    }

    // -----------------------------------------------------------------------------------------------------
    // Pin / hide strip (long-press OK)
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun longPressOnAnUnpinnedAppOffersPinAndHideAndPinPersists() {
        val fx = show() // App 01..06 pinned
        rule.pressRepeatedly(Key.DirectionDown, 2)
        rule.assertFocused("App 09")

        rule.longPressOk()

        rule.onNodeWithText("PIN").assertExists()
        rule.onNodeWithText("HIDE").assertExists()
        rule.onNodeWithText("UNPIN").assertDoesNotExist()
        assertEquals("long press must not launch", emptyList<Any>(), fx.launched.toList())
        rule.assertFocused("PIN")
        rule.pressOk()
        rule.waitForIdle()

        assertTrue("App 09" in fx.pinnedLabels())
        rule.assertFocused("App 09")
    }

    @Test
    fun longPressOnAPinnedAppOffersUnpin() {
        val fx = show()

        rule.longPressOk() // App 01 is pinned

        rule.onNodeWithText("UNPIN").assertExists()
        rule.assertFocused("UNPIN")
        rule.pressOk()
        rule.waitForIdle()

        assertFalse("App 01" in fx.pinnedLabels())
        rule.assertFocused("App 01")
    }

    @Test
    fun hideFromTheStripRemovesTheAppAndFocusSurvivesOnThePreviousIndex() {
        val fx = show()
        rule.pressRight()
        rule.longPressOk() // App 02
        rule.assertFocused("UNPIN")
        rule.pressRight()
        rule.assertFocused("HIDE")

        rule.pressOk()
        rule.waitForIdle()

        assertEquals(listOf("App 02"), fx.hiddenLabels())
        rule.onNodeWithText("App 02").assertDoesNotExist()
        // The tile that moved into index 1 is App 03.
        rule.assertFocused("App 03")
    }

    @Test
    fun backClosesTheStripFirstAndNeverLeavesTheDrawer() {
        show()
        rule.longPressOk()
        rule.assertFocused("UNPIN")
        sound.clear()

        rule.pressBack()

        assertEquals("the strip consumed Back", 0, backs)
        rule.onNodeWithText("UNPIN").assertDoesNotExist()
        rule.assertFocused("App 01")
        assertEquals(1, sound.backCount)

        rule.pressBack()
        assertEquals(1, backs)
    }

    // -----------------------------------------------------------------------------------------------------
    // Focus survival
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun focusSurvivesHidingTheFocusedAppFromOutside() {
        val fx = show()
        rule.pressRepeatedly(Key.DirectionDown, 2)
        rule.assertFocused("App 09")

        fx.hide("App 09")
        rule.waitForIdle()

        rule.assertSingleFocus()
        rule.assertFocused("App 10")
    }

    @Test
    fun focusSurvivesHidingTheLastAppByFallingBackOneIndex() {
        val fx = show()
        rule.pressRepeatedly(Key.DirectionDown, 4)
        rule.pressRepeatedly(Key.DirectionRight, 3)
        rule.assertFocused("App 20")

        fx.hide("App 20")
        rule.waitForIdle()

        rule.assertSingleFocus()
        rule.assertFocused("App 19")
    }

    @Test
    fun focusSurvivesAFilterChangeAfterScrollingDeepIntoTheGrid() {
        show()
        rule.pressRepeatedly(Key.DirectionDown, 4)
        rule.assertFocused("App 17")

        rule.runOnUiThread { category = AppCategory.TOOLS } // four apps, index 16 clamps to 3
        rule.waitForIdle()

        rule.assertSingleFocus()
        rule.assertFocused("App 19")
    }

    @Test
    fun focusSurvivesAFilterChangeWhileTheDrawerHasFocus() {
        show()
        rule.pressRepeatedly(Key.DirectionDown, 2)
        rule.assertFocused("App 09")

        rule.runOnUiThread { category = AppCategory.TOOLS } // App 04, 09, 14, 19: index 8 clamps to 3
        rule.waitForIdle()

        rule.assertFocused("App 19")
    }
}

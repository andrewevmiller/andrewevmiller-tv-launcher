package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.home.apps.AppGridState
import com.andrewevmiller.tvlauncher.home.apps.AppsSection
import com.andrewevmiller.tvlauncher.home.apps.rememberAppGridState
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.PreviewApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edit mode of the Home grid (BUILD_PLAN section 5.4): long-press OK lifts a tile, arrows move it, OK drops it and
 * the move is persisted through `AppsRepository.move`, Back cancels.
 *
 * The long press is produced the way the framework does it for a held key: `keyDown`, `advanceEventTime(700)`,
 * `keyUp`. While a key stays down the test dispatcher emits auto-repeat events (first after 500 ms), and the
 * grid's PressTracker turns the first repeat into one long press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class AppGridEditModeBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private var category by mutableStateOf<AppCategory?>(null)
    private lateinit var state: AppGridState
    private var drawerOpened = 0

    private fun show(fx: RepoFixture = RepoFixture(PreviewApps)): RepoFixture {
        rule.setContentWithSound(sound) {
            state = rememberAppGridState()
            AppsSection(
                repository = fx.repo,
                category = category,
                onOpenDrawer = { drawerOpened++ },
                state = state,
            )
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("All apps").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        return fx
    }

    private fun focusCell(index: Int) {
        rule.runOnIdle { check(state.requestFocus(index)) }
        rule.waitForIdle()
        sound.clear()
    }

    private fun assertLifted() {
        rule.onNodeWithText("MOVING", substring = true).assertExists()
        assertTrue(state.isEditing)
    }

    private fun assertNotEditing() {
        rule.onAllNodesWithText("MOVING", substring = true).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        assertFalse(state.isEditing)
    }

    @Test
    fun longPressOkLiftsTheTileAndAShortPressStillLaunches() {
        val fx = show()
        focusCell(1) // Netflix

        rule.longPressOk()

        assertLifted()
        assertEquals("the long press must not also launch", emptyList<Any>(), fx.launched.toList())
        assertEquals(emptyList<Any>(), sound.launches)
        assertEquals("lifting plays the select sound", 1, sound.selectCount)
        rule.assertFocused("Netflix")
    }

    @Test
    fun aShortPressDoesNotEnterEditMode() {
        val fx = show()
        focusCell(1)

        rule.pressOk()

        assertNotEditing()
        assertEquals(1, fx.launched.size)
    }

    @Test
    fun arrowsMoveTheLiftedTileAndFocusFollowsIt() {
        show()
        focusCell(0) // YouTube
        rule.longPressOk()

        rule.pressRight()
        rule.assertFocused("YouTube")
        rule.pressRight()
        rule.assertFocused("YouTube")

        assertLifted()
        // Working order: Netflix, Plex, YouTube, Spotify, ... so YouTube now sits where Plex was.
        val netflix = rule.onNodeWithText("Netflix").fetchSemanticsNode().boundsInRoot.left
        val youtube = rule.onNodeWithText("YouTube").fetchSemanticsNode().boundsInRoot.left
        val plex = rule.onNodeWithText("Plex").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Netflix is first, Plex second, YouTube third", netflix < plex && plex < youtube)
    }

    @Test
    fun okDropsTheTileAndPersistsTheNewOrderThroughRepositoryMove() {
        val fx = show()
        focusCell(0) // YouTube
        rule.longPressOk()
        rule.pressRight()
        rule.pressRight()

        rule.pressOk() // drop
        rule.waitForIdle()

        assertEquals(
            listOf("Netflix", "Plex", "YouTube", "Spotify", "Stadia Notes", "Files"),
            fx.pinnedLabels(),
        )
        assertNotEditing()
        assertEquals("dropping must not launch the app", emptyList<Any>(), fx.launched.toList())
        rule.assertFocused("YouTube")
    }

    @Test
    fun downMovesTheLiftedTileOneGridRowAndDropPersistsIt() {
        val fx = show()
        focusCell(0) // YouTube
        rule.longPressOk()

        rule.pressDown() // +4 positions
        rule.assertFocused("YouTube")
        rule.pressOk()
        rule.waitForIdle()

        assertEquals(
            listOf("Netflix", "Plex", "Spotify", "Stadia Notes", "YouTube", "Files"),
            fx.pinnedLabels(),
        )
    }

    @Test
    fun upMovesTheLiftedTileBackOneGridRow() {
        val fx = show()
        focusCell(5) // Files, row 1 column 1
        rule.longPressOk()

        rule.pressUp()
        rule.pressOk()
        rule.waitForIdle()

        assertEquals(
            listOf("YouTube", "Files", "Netflix", "Plex", "Spotify", "Stadia Notes"),
            fx.pinnedLabels(),
        )
    }

    @Test
    fun backCancelsEditModeAndPersistsNothing() {
        val fx = show()
        val before = fx.pinnedLabels()
        focusCell(0)
        rule.longPressOk()
        rule.pressRight()
        rule.pressRight()
        sound.clear()

        rule.pressBack()

        assertNotEditing()
        assertEquals(before, fx.pinnedLabels())
        assertEquals("cancel plays the back sound", 1, sound.backCount)
        // Focus returns to the tile at the index the lift started from.
        rule.assertFocused("YouTube")
        val youtube = rule.onNodeWithText("YouTube").fetchSemanticsNode().boundsInRoot.left
        val netflix = rule.onNodeWithText("Netflix").fetchSemanticsNode().boundsInRoot.left
        assertTrue("the original order is shown again", youtube < netflix)
    }

    @Test
    fun droppingATileWhereItStartedDoesNotCallMove() {
        val fx = show()
        val before = fx.layout()
        focusCell(2)
        rule.longPressOk()

        rule.pressOk()
        rule.waitForIdle()

        assertNotEditing()
        assertEquals(before, fx.layout())
    }

    @Test
    fun movingToTheEdgeOfTheListPlaysEdgeInsteadOfMoving() {
        show()
        focusCell(0)
        rule.longPressOk()
        sound.clear()

        rule.pressLeft()
        rule.pressUp()

        assertEquals(2, sound.edgeCount)
        rule.assertFocused("YouTube")
    }

    @Test
    fun theActionStripAppearsWhileLiftedAndUnpinRemovesTheApp() {
        val fx = show()
        focusCell(2) // Plex
        rule.longPressOk()

        rule.onNodeWithText("UNPIN").assertExists()
        rule.onNodeWithText("HIDE").assertExists()

        // Down from the last grid row of the lifted tile goes into the strip; Plex is in row 0 of 2, so go down first.
        rule.pressDown()
        rule.pressDown()
        rule.assertFocused("UNPIN")
        rule.pressOk()
        rule.waitForIdle()

        assertEquals(listOf("YouTube", "Netflix", "Spotify", "Stadia Notes", "Files"), fx.pinnedLabels())
        assertNotEditing()
        // The unpinned app is still installed, only not pinned.
        assertTrue("Plex" in fx.allLabels())
    }

    @Test
    fun hideFromTheActionStripHidesTheApp() {
        val fx = show()
        focusCell(5) // Files, already in the last row
        rule.longPressOk()

        rule.pressDown()
        rule.assertFocused("UNPIN")
        rule.pressRight()
        rule.assertFocused("HIDE")
        rule.pressOk()
        rule.waitForIdle()

        assertEquals(listOf("Files"), fx.hiddenLabels())
        assertFalse("Files" in fx.pinnedLabels())
        assertNotEditing()
    }

    @Test
    fun editModeWithACategoryFilterMapsTheMoveOntoTheFullPinnedOrder() {
        val fx = show()
        rule.runOnUiThread { category = AppCategory.VIDEO } // YouTube, Netflix, Plex
        rule.waitForIdle()
        focusCell(0) // YouTube
        rule.longPressOk()

        rule.pressRight()
        rule.pressRight() // behind Plex
        rule.pressOk()
        rule.waitForIdle()

        // Spotify, Stadia Notes and Files keep their places behind the video apps.
        assertEquals(
            listOf("Netflix", "Plex", "YouTube", "Spotify", "Stadia Notes", "Files"),
            fx.pinnedLabels(),
        )
    }
}

package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.home.HomeCategoryChips
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.ui.components.ChipOption
import com.andrewevmiller.tvlauncher.ui.components.ChipsRow
import com.andrewevmiller.tvlauncher.ui.components.FeatureBand
import com.andrewevmiller.tvlauncher.ui.components.GalleryItem
import com.andrewevmiller.tvlauncher.ui.components.GalleryRow
import com.andrewevmiller.tvlauncher.ui.components.RecentRow
import com.andrewevmiller.tvlauncher.ui.components.TimelineItem
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The horizontal rows of ui/components: `ChipsRow`, `RecentRow`, `GalleryRow` and the `FeatureBand` CTA. Their
 * contract (BUILD_PLAN section 5.4 and the components' docs): Left/Right move inside the row, the row ends are
 * edges that consume the key and play `FieldSound.edge()` once per press, OK plays `select()` and reports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class RowsBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()

    // -----------------------------------------------------------------------------------------------------
    // ChipsRow
    // -----------------------------------------------------------------------------------------------------

    private val chips = listOf(
        ChipOption("all", "All"),
        ChipOption("video", "Video"),
        ChipOption("music", "Music"),
    )

    private var selectedChip by mutableStateOf("all")
    private val chosen = mutableListOf<String>()

    private fun showChips() {
        rule.setContentWithSound(sound) {
            Column {
                FocusableStub("Above")
                ChipsRow(
                    options = chips,
                    selected = selectedChip,
                    onSelect = {
                        chosen += it
                        selectedChip = it
                    },
                )
                FocusableStub("Below")
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun chipsNothingIsFocusedByDefaultSoTheSelectedChipIsNotTheFocusedOne() {
        showChips()

        rule.assertNoFocus()
        rule.onNodeWithText("All").assertExists() // the selected chip is drawn, but not focused
    }

    @Test
    fun chipsRightAndLeftMoveBetweenChips() {
        showChips()
        rule.focus("All")
        sound.clear()

        rule.pressRight()
        rule.assertFocused("Video")
        rule.pressRight()
        rule.assertFocused("Music")
        rule.pressLeft()
        rule.assertFocused("Video")

        assertEquals(0, sound.edgeCount)
        assertEquals("one tick per gained focus", 3, sound.ticks.size)
    }

    @Test
    fun chipsLeftOnTheFirstChipIsAConsumedEdge() {
        showChips()
        rule.focus("All")
        sound.clear()

        rule.pressLeft()

        rule.assertFocused("All")
        assertEquals(1, sound.edgeCount)
        assertEquals(emptyList<Int>(), sound.ticks)
    }

    @Test
    fun chipsRightOnTheLastChipIsAConsumedEdge() {
        showChips()
        rule.focus("Music")
        sound.clear()

        rule.pressRight()
        rule.pressRight()

        rule.assertFocused("Music")
        assertEquals("one edge per press", 2, sound.edgeCount)
    }

    @Test
    fun chipsHoldingRightAtTheLastChipPlaysEdgeOnlyOnce() {
        showChips()
        rule.focus("Music")
        sound.clear()

        rule.hold(Key.DirectionRight)

        rule.assertFocused("Music")
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun chipsOkSelectsTheFocusedChipWithOnSelectAndTheSelectSound() {
        showChips()
        rule.focus("All")
        rule.pressRight()
        sound.clear()

        rule.pressOk()

        assertEquals(listOf("video"), chosen)
        assertEquals(1, sound.selectCount)
        assertEquals("selecting is not launching", emptyList<Any>(), sound.launches)
        rule.assertFocused("Video")
    }

    @Test
    fun chipsEnterKeyAlsoSelects() {
        showChips()
        rule.focus("Music")
        sound.clear()

        rule.pressEnter()

        assertEquals(listOf("music"), chosen)
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun chipsUpAndDownLeaveTheRowToTheNeighbouringSections() {
        showChips()
        rule.focus("All")

        rule.pressUp()
        rule.assertFocused("Above")
        rule.pressDown()
        rule.assertFocused("All")
        rule.pressDown()
        rule.assertFocused("Below")
    }

    @Test
    fun homeCategoryChipsAreAllVideoMusicGamesTools() {
        assertEquals(
            listOf("All", "Video", "Music", "Games", "Tools"),
            HomeCategoryChips.map { it.label },
        )
        assertEquals(listOf(null, AppCategory.VIDEO, AppCategory.MUSIC, AppCategory.GAMES, AppCategory.TOOLS), HomeCategoryChips.map { it.value })
    }

    // -----------------------------------------------------------------------------------------------------
    // RecentRow
    // -----------------------------------------------------------------------------------------------------

    private val recentItems = listOf(
        TimelineItem("a", "10-21", "18:05", FieldIcons.Play, "Plex", "Video"),
        TimelineItem("b", "10-20", "21:40", FieldIcons.Note, "Spotify", "Music"),
        TimelineItem("c", "10-19", "09:12", FieldIcons.Pad, "Stadia Notes", "Games"),
    )

    private var recentExpanded by mutableStateOf(false)
    private var toggles by mutableIntStateOf(0)
    private val opened = mutableListOf<String>()

    private fun showRecent(expanded: Boolean = false) {
        recentExpanded = expanded
        rule.setContentWithSound(sound) {
            Column {
                RecentRow(
                    items = recentItems,
                    expanded = recentExpanded,
                    onToggle = {
                        toggles++
                        recentExpanded = !recentExpanded
                    },
                    onOpen = { opened += it.key },
                )
                FocusableStub("Below")
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun recentCollapsedShowsOnlyTheToggleChip() {
        showRecent()

        rule.onNodeWithText("Show recent").assertExists()
        recentItems.forEach { rule.onNodeWithText(it.title).assertDoesNotExist() }
        rule.onNodeWithText("Hide").assertDoesNotExist()
    }

    @Test
    fun recentEnterOnTheToggleCallsOnToggleAndExpandsTheRow() {
        showRecent()
        rule.focus("Show recent")
        sound.clear()

        rule.pressOk()

        assertEquals(1, toggles)
        assertEquals(1, sound.selectCount)
        rule.onNodeWithText("Hide").assertExists()
        recentItems.forEach { rule.onNodeWithText(it.title).assertExists() }
        rule.onNodeWithText("Show recent").assertDoesNotExist()
    }

    @Test
    fun recentToggleKeepsFocusAcrossTheChangeAndCollapsesAgain() {
        showRecent()
        rule.focus("Show recent")
        rule.pressOk()
        rule.assertFocused("Hide")

        rule.pressOk()

        assertEquals(2, toggles)
        rule.onNodeWithText("Show recent").assertExists()
        recentItems.forEach { rule.onNodeWithText(it.title).assertDoesNotExist() }
    }

    @Test
    fun recentCollapsedRowEdgesOnBothSides() {
        showRecent()
        rule.focus("Show recent")
        sound.clear()

        rule.pressLeft()
        rule.pressRight()

        rule.assertFocused("Show recent")
        assertEquals("the lone toggle is both the first and the last item", 2, sound.edgeCount)
    }

    @Test
    fun recentExpandedWalksAcrossEntriesAndEdgesAtBothEnds() {
        showRecent(expanded = true)
        rule.focus("Hide")
        sound.clear()

        rule.pressLeft() // first item: edge
        rule.assertFocused("Hide")
        assertEquals(1, sound.edgeCount)

        rule.pressRight()
        rule.assertFocused("Plex")
        rule.pressRight()
        rule.assertFocused("Spotify")
        rule.pressRight()
        rule.assertFocused("Stadia Notes")
        assertEquals(1, sound.edgeCount)

        rule.pressRight() // last item: edge
        rule.assertFocused("Stadia Notes")
        assertEquals(2, sound.edgeCount)
    }

    @Test
    fun recentEnterOnAnEntryOpensItAndPlaysSelect() {
        showRecent(expanded = true)
        rule.focus("Spotify")
        sound.clear()

        rule.pressOk()

        assertEquals(listOf("b"), opened)
        assertEquals(1, sound.selectCount)
        assertEquals(0, toggles)
    }

    @Test
    fun recentEntriesCanBeOpenedWithoutTheSelectSoundWhenTheHandlerPlaysTheLaunchMotif() {
        recentExpanded = true
        rule.setContentWithSound(sound) {
            RecentRow(
                items = recentItems,
                expanded = recentExpanded,
                onToggle = {},
                onOpen = { opened += it.key },
                playSelect = false,
            )
        }
        rule.waitForIdle()
        rule.focus("Plex")
        sound.clear()

        rule.pressOk()

        assertEquals(listOf("a"), opened)
        assertEquals(0, sound.selectCount)
    }

    @Test
    fun recentDownLeavesTheRow() {
        showRecent()
        rule.focus("Show recent")

        rule.pressDown()

        rule.assertFocused("Below")
    }

    // -----------------------------------------------------------------------------------------------------
    // GalleryRow (screensaver tiles)
    // -----------------------------------------------------------------------------------------------------

    private val photos = listOf(
        GalleryItem("photos", "Google Photos · slideshow"),
        GalleryItem("oxyfield", "Oxyfield · dot field"),
    )
    private val picked = mutableListOf<String>()

    private fun showGallery(playSelect: Boolean = true) {
        rule.setContentWithSound(sound) {
            GalleryRow(items = photos, onSelect = { picked += it.key }, playSelect = playSelect)
        }
        rule.waitForIdle()
    }

    @Test
    fun galleryRightAndLeftMoveAndEdgeAtBothEnds() {
        showGallery()
        rule.focus("Google Photos · slideshow")
        sound.clear()

        rule.pressLeft()
        assertEquals(1, sound.edgeCount)
        rule.pressRight()
        rule.assertFocused("Oxyfield · dot field")
        rule.pressRight()
        assertEquals(2, sound.edgeCount)
        rule.assertFocused("Oxyfield · dot field")
    }

    @Test
    fun galleryOkSelectsTheFocusedPrint() {
        showGallery()
        rule.focus("Oxyfield · dot field")
        sound.clear()

        rule.pressOk()

        assertEquals(listOf("oxyfield"), picked)
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun galleryOkWithPlaySelectOffStaysSilentForTheCaller() {
        showGallery(playSelect = false)
        rule.focus("Google Photos · slideshow")
        sound.clear()

        rule.pressOk()

        assertEquals(listOf("photos"), picked)
        assertEquals(0, sound.selectCount)
    }

    // -----------------------------------------------------------------------------------------------------
    // FeatureBand CTA
    // -----------------------------------------------------------------------------------------------------

    private var ctaPressed = 0

    private fun showBand(playSelect: Boolean = true) {
        rule.setContentWithSound(sound) {
            Column {
                FeatureBand(
                    kicker = "Last opened",
                    title = "Plex",
                    body = "Pick up where you left off.",
                    ctaLabel = "Open →",
                    onCta = { ctaPressed++ },
                    playSelect = playSelect,
                )
                FocusableStub("Below")
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun bandCtaOkCallsOnCtaAndPlaysSelect() {
        showBand()
        rule.focus("Open →")
        sound.clear()

        rule.pressOk()

        assertEquals(1, ctaPressed)
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun bandCtaWithPlaySelectOffDoesNotPlaySelect() {
        showBand(playSelect = false)
        rule.focus("Open →")
        sound.clear()

        rule.pressOk()

        assertEquals(1, ctaPressed)
        assertEquals(0, sound.selectCount)
    }

    @Test
    fun bandCtaIsALoneItemSoLeftAndRightAreEdges() {
        showBand()
        rule.focus("Open →")
        sound.clear()

        rule.pressLeft()
        rule.pressRight()

        rule.assertFocused("Open →")
        assertEquals(2, sound.edgeCount)
        assertFalse(ctaPressed > 0)
    }

    @Test
    fun bandCtaDownReachesTheNextSection() {
        showBand()
        rule.focus("Open →")

        rule.pressDown()

        rule.assertFocused("Below")
    }
}

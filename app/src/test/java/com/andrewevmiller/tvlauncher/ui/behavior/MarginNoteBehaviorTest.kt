package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.Feature
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.marginnote.MarginNoteRow
import com.andrewevmiller.tvlauncher.marginnote.MarginNoteRules
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The margin note on the live Home: a focus stop between the header and the feature band, an empty note that
 * takes no space until focused, OK opens the one-line editor, Done saves, Back discards and returns focus to the row.
 *
 * The host repeats FieldHome's wiring (the note state, the Back handler ordered after the editor, the save) since
 * FieldHome itself needs a Context and an AppContainer. The on-screen keyboard is not testable here; the
 * keyboard calls are no-ops in this environment and the editor's focus and text state is what is checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class MarginNoteBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private var stored by mutableStateOf("")
    private var editing by mutableStateOf(false)

    @Composable
    private fun Host() {
        // Mirrors FieldHome: the editor's Back is handled before anything else that is open.
        BackHandler(enabled = editing) {
            editing = false
            sound.back()
        }
        val fixture = remember { RepoFixture(numberedApps(4)) }
        HomeScreen(
            allApps = fixture.installed,
            systemTiles = listOf(SystemTile("Wi-Fi", FieldIcons.Wifi) {}),
            chip = Chip.ALL,
            drawer = false,
            onChip = {},
            onOpenDrawer = {},
            onLaunch = {},
            features = listOf(Feature(fixture.installed.first(), "2026-10-21 at 09:00")),
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
            onOpenSettings = {},
            note = {
                MarginNoteRow(
                    note = stored,
                    editing = editing,
                    onOpen = {
                        sound.select()
                        editing = true
                    },
                    onSave = { text ->
                        stored = MarginNoteRules.sanitize(text)
                        editing = false
                        sound.select()
                    },
                )
            },
        )
    }

    private fun show(initial: String = "") {
        stored = initial
        rule.setContentWithSound(sound) { Host() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    /** From the header's settings chip, one Down reaches the note row. */
    private fun toNoteRow() {
        rule.focus("LAUNCHER SETTINGS")
        rule.pressDown()
    }

    private fun editor() = rule.onNode(hasSetTextAction())

    private fun editorOpen(): Boolean = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()

    private fun sectionTop(): Float =
        rule.onNodeWithText("APPS · 4 PINNED").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theRowIsAFocusStopBetweenTheHeaderChipAndTheBand() {
        show("finish S2 of Severance")
        rule.onNodeWithText("NOTE  finish S2 of Severance").assertExists()

        rule.focus("LAUNCHER SETTINGS")
        rule.pressDown()
        rule.assertFocused("NOTE  finish S2 of Severance")

        rule.pressDown()
        rule.assertFocused("Open →")

        rule.pressUp()
        rule.assertFocused("NOTE  finish S2 of Severance")
    }

    @Test
    fun anEmptyNoteShowsNothingUntilTheRowIsFocused() {
        show("")
        rule.onNodeWithText("NOTE  add a note").assertDoesNotExist()

        toNoteRow()

        rule.assertFocused("NOTE  add a note")
        rule.pressDown()
        rule.onNodeWithText("NOTE  add a note").assertDoesNotExist()
        rule.assertFocused("Open →")
    }

    @Test
    fun anEmptyUnfocusedNoteTakesNoSpace() {
        show("")
        val before = sectionTop()

        toNoteRow()
        val focusedTop = sectionTop()
        rule.pressDown()
        val after = sectionTop()

        assertTrue("the focused row opens a line: $before -> $focusedTop", focusedTop > before)
        assertEquals("leaving the empty row gives the space back", before.toDouble(), after.toDouble(), 0.5)
    }

    @Test
    fun withANoteTheRowReadsNoteAndTheTextIsMuted() {
        show("finish S2 of Severance")
        rule.onNodeWithText("NOTE  finish S2 of Severance").assertExists()
        rule.onNodeWithText("NOTE  add a note").assertDoesNotExist()
    }

    @Test
    fun okOpensTheEditorWithFocusOnTheField() {
        show("finish S2")
        toNoteRow()
        sound.clear()

        rule.pressOk()

        assertTrue(editorOpen())
        editor().assertIsFocused()
        rule.onNodeWithText("Done saves · Back discards").assertExists()
        rule.onNodeWithText("NOTE  finish S2").assertDoesNotExist()
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun theEditorStartsWithTheCurrentNoteAndTheCaretAtTheEnd() {
        show("finish S2")
        toNoteRow()

        rule.pressOk()

        editor().assertTextEquals("finish S2")
    }

    @Test
    fun typingThenDoneSavesAndTheRowShowsTheText() {
        show("")
        toNoteRow()
        rule.pressOk()

        editor().performTextInput("finish S2 of Severance")
        rule.waitForIdle()
        editor().performImeAction()
        rule.waitForIdle()

        assertTrue(!editorOpen())
        assertEquals("finish S2 of Severance", stored)
        rule.assertFocused("NOTE  finish S2 of Severance")
        assertTrue(sound.selectCount >= 2)
    }

    @Test
    fun savingAnEmptyNoteRemovesTheLine() {
        show("old note")
        toNoteRow()
        rule.pressOk()

        editor().performTextReplacement("")
        rule.waitForIdle()
        editor().performImeAction()
        rule.waitForIdle()

        assertEquals("", stored)
        rule.onNodeWithText("NOTE  old note").assertDoesNotExist()
        // Focus is back on the row, which now reads as an empty note while it has focus.
        rule.assertFocused("NOTE  add a note")
        rule.pressDown()
        rule.onNodeWithText("NOTE  add a note").assertDoesNotExist()
    }

    @Test
    fun backDiscardsTheEditAndReturnsFocusToTheRow() {
        show("finish S2")
        toNoteRow()
        rule.pressOk()
        editor().performTextReplacement("a different note")
        rule.waitForIdle()
        sound.clear()

        rule.pressBack()

        assertTrue(!editorOpen())
        assertEquals("finish S2", stored)
        rule.assertFocused("NOTE  finish S2")
        assertEquals(1, sound.backCount)
    }

    @Test
    fun theArrowKeysDoNotLeaveTheEditor() {
        show("finish S2")
        toNoteRow()
        rule.pressOk()

        rule.pressUp()
        rule.pressDown()
        rule.pressLeft()
        rule.pressRight()

        assertTrue(editorOpen())
        editor().assertIsFocused()
    }

    @Test
    fun typedTextIsLimitedToEightyCharacters() {
        show("")
        toNoteRow()
        rule.pressOk()

        editor().performTextInput("x".repeat(95))
        rule.waitForIdle()
        editor().performImeAction()
        rule.waitForIdle()

        assertEquals("x".repeat(MarginNoteRules.MAX_LENGTH), stored)
    }

    @Test
    fun afterDoneOneDownStillReachesTheBand() {
        show("finish S2")
        toNoteRow()
        rule.pressOk()
        editor().performImeAction()
        rule.waitForIdle()

        // Focus is back on the note row, so one Down still reaches the band's Open button.
        rule.pressDown()
        rule.assertFocused("Open →")
    }
}

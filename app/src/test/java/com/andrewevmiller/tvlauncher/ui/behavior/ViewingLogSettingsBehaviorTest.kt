package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsScreen
import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
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
 * The Settings "Viewing log" section: the on/off row writes the setting, and Clear log needs a second OK. Moving
 * focus away from Clear log un-asks it, so a stray OK later does not clear the log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class ViewingLogSettingsBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private val repo = FakeSettingsRepository(LauncherSettings(reducedMotion = true))
    private var cleared = 0

    private fun show() {
        rule.setContentWithSound(sound) {
            SettingsScreen(
                settings = repo,
                overrides = FakeMotifOverrides(),
                apps = RepoFixture(numberedApps(3)).repo,
                screensaver = NoOpScreensaverController,
                onBack = {},
                onClearViewingLog = { cleared++ },
            )
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    /** Walks Down from the top of Settings until [text] has focus; the rows are a lazy list, so this is how a user gets there. */
    private fun walkTo(text: String) {
        var steps = 0
        while (rule.focusedTexts().firstOrNull() != text) {
            check(steps++ < 120) { "never reached \"$text\"" }
            rule.pressDown()
        }
    }

    private fun stored(): LauncherSettings = runBlocking { repo.settings.first() }

    @Test
    fun theViewingLogRowTurnsTheLogOffAndOn() {
        show()
        assertTrue(stored().viewingLogEnabled)

        walkTo("Viewing log")
        rule.pressOk()
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        assertFalse(stored().viewingLogEnabled)

        rule.pressOk()
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        assertTrue(stored().viewingLogEnabled)
    }

    @Test
    fun clearLogNeedsASecondOk() {
        show()
        walkTo("Clear log")

        rule.pressOk()
        rule.onNodeWithText("Press OK again to clear the log").assertExists()
        assertEquals(0, cleared)

        rule.pressOk()
        assertEquals(1, cleared)
        rule.onNodeWithText("Clear log").assertExists()
    }

    @Test
    fun movingAwayUnasksClearLog() {
        show()
        walkTo("Clear log")
        rule.pressOk()
        rule.onNodeWithText("Press OK again to clear the log").assertExists()

        rule.pressUp()
        rule.pressDown()

        // Back on the row it is asked afresh: the first OK arms it, and nothing has been cleared.
        rule.onNodeWithText("Clear log").assertExists()
        rule.pressOk()
        assertEquals(0, cleared)
        rule.onNodeWithText("Press OK again to clear the log").assertExists()
    }
}

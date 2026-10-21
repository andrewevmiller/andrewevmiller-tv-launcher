package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.home.data.PreviewApps
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.Screensaver
import com.andrewevmiller.tvlauncher.settings.SettingsScreen
import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
import com.andrewevmiller.tvlauncher.sound.MotifOverride
import com.andrewevmiller.tvlauncher.sound.Waveform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings (G2s): the real `SettingsScreen` over the in-memory settings repository, motif overrides, apps and a
 * no-op screensaver controller. Rows from the top: Dark, Light, Reduced motion, Dot-grid brightness, Interface
 * sounds, the waveform pairs, the screensaver choices, Start now, About, then one Re-roll / Mute pair per app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class SettingsBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private val apps = RepoFixture(PreviewApps)
    private var backs = 0

    private class RecordingScreensaver(override val canSwitch: Boolean = true) : ScreensaverController {
        val applied = mutableListOf<Screensaver>()
        var started = 0
        override fun apply(choice: Screensaver) { applied += choice }
        override fun startNow(): Boolean { started++; return true }
    }

    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var overrides: FakeMotifOverrides

    /** Reduced motion is on in the stored settings, so the screen's own theme snaps its animations. */
    private fun show(
        settings: LauncherSettings = LauncherSettings(reducedMotion = true),
        motifs: Map<String, MotifOverride> = emptyMap(),
        screensaver: ScreensaverController = NoOpScreensaverController,
    ) {
        settingsRepo = FakeSettingsRepository(settings)
        overrides = FakeMotifOverrides(motifs)
        rule.setContentWithSound(sound) {
            SettingsScreen(
                settings = settingsRepo,
                overrides = overrides,
                apps = apps.repo,
                screensaver = screensaver,
                onBack = { backs++ },
            )
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun stored(): LauncherSettings = runBlocking { settingsRepo.settings.first() }
    private fun storedOverrides(): Map<String, MotifOverride> = runBlocking { overrides.overrides.first() }

    /** Lets the screen's 150 ms "settle" delay before a preview or a re-roll sound run out. */
    private fun letSoundSettle() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    /** Presses Down until the focused element's first text is [label]; focus must never be lost on the way. */
    private fun downUntil(label: String, max: Int = 40) {
        repeat(max) {
            if (rule.focusedTexts().firstOrNull() == label) return
            rule.pressDown()
            rule.assertSingleFocus()
        }
        assertEquals("never reached $label", label, rule.focusedTexts().firstOrNull())
    }

    private fun goToDotBrightness() {
        rule.assertFocused("Dark")
        rule.pressRepeatedly(Key.DirectionDown, 3)
        rule.assertFocused("Dot-grid brightness")
    }

    /** Walks Down to the waveform pair rows and across to [label]. Works for any option. */
    private fun goToWaveform(label: String) {
        downUntil("Interface sounds")
        rule.pressDown() // first waveform pair
        repeat(6) {
            if (rule.focusedTexts().firstOrNull() == label) return
            // Pairs are [Sine Triangle] [Saw Square] [Supersaw White noise] [Pink noise]; try the row, then below.
            rule.pressRight()
            if (rule.focusedTexts().firstOrNull() == label) return
            rule.pressLeft()
            rule.pressDown()
        }
        rule.assertFocused(label)
    }

    // -----------------------------------------------------------------------------------------------------
    // Arrival
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun firstFocusLandsOnDark() {
        show()

        rule.assertFocused("Dark")
    }

    @Test
    fun enteringTheScreenDoesNotPlayAPreview() {
        show()
        letSoundSettle()

        assertEquals(0, sound.previewCount)
        assertEquals(emptyList<Any>(), sound.launches)
    }

    @Test
    fun enteringWithAnotherStoredWaveformStillDoesNotPlayAPreview() {
        show(LauncherSettings(reducedMotion = true, waveform = Waveform.SUPERSAW))
        letSoundSettle()

        assertEquals(0, sound.previewCount)
    }

    @Ignore(
        "BUG: SettingsScreen.kt has no handler for Up at the first row or Down at the last: focus stays put but " +
            "FieldSound.edge() is not played (Home plays it, HomeScreen.kt:75-82); spec gap, see report",
    )
    @Test
    fun upFromTheFirstRowPlaysEdge() {
        show()
        sound.clear()

        rule.pressUp()

        rule.assertFocused("Dark")
        assertEquals(1, sound.edgeCount)
    }

    // -----------------------------------------------------------------------------------------------------
    // Appearance
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun okOnLightStoresLightModeAndPlaysSelect() {
        show()
        rule.pressDown()
        rule.assertFocused("Light")
        sound.clear()

        rule.pressOk()

        assertEquals(false, stored().darkMode)
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun okOnReducedMotionTogglesIt() {
        show(LauncherSettings(reducedMotion = false))
        rule.pressRepeatedly(Key.DirectionDown, 2)
        rule.assertFocused("Reduced motion")

        rule.pressOk()
        rule.waitForIdle()

        assertEquals(true, stored().reducedMotion)
    }

    @Test
    fun rightOnTheDotBrightnessRowStepsTheAlphaByOneTenth() {
        show(LauncherSettings(reducedMotion = true, dotGridAlpha = 0.5f))
        goToDotBrightness()

        rule.pressRight()
        assertEquals(0.6f, stored().dotGridAlpha, 0.0001f)
        rule.pressRight()
        assertEquals(0.7f, stored().dotGridAlpha, 0.0001f)
        rule.pressLeft()
        assertEquals(0.6f, stored().dotGridAlpha, 0.0001f)
        rule.assertFocused("Dot-grid brightness")
    }

    @Test
    fun leftOnTheDotBrightnessRowAtZeroPlaysEdgeAndKeepsTheValue() {
        show(LauncherSettings(reducedMotion = true, dotGridAlpha = 0.0f))
        goToDotBrightness()
        sound.clear()

        rule.pressLeft()

        assertEquals(0.0f, stored().dotGridAlpha, 0.0001f)
        assertEquals(1, sound.edgeCount)
        rule.assertFocused("Dot-grid brightness")
    }

    @Test
    fun rightOnTheDotBrightnessRowAtFullPlaysEdgeAndKeepsTheValue() {
        show() // the default alpha is 1.0
        goToDotBrightness()
        sound.clear()

        rule.pressRight()

        assertEquals(1.0f, stored().dotGridAlpha, 0.0001f)
        assertEquals(1, sound.edgeCount)
    }

    @Test
    fun steppingDownFromFullAndBackUpHasNoDrift() {
        show()
        goToDotBrightness()

        repeat(10) { rule.pressLeft() }
        assertEquals(0.0f, stored().dotGridAlpha, 0.0001f)
        repeat(10) { rule.pressRight() }

        assertEquals(1.0f, stored().dotGridAlpha, 0.0001f)
    }

    @Test
    fun okOnTheDotBrightnessRowCyclesOneTenthUp() {
        show(LauncherSettings(reducedMotion = true, dotGridAlpha = 0.3f))
        goToDotBrightness()

        rule.pressOk()

        assertEquals(0.4f, stored().dotGridAlpha, 0.0001f)
    }

    // -----------------------------------------------------------------------------------------------------
    // Sound
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun choosingAWaveformUpdatesTheRepositoryAndPlaysThePreviewExactlyOnce() {
        show() // Saw is the default
        goToWaveform("Square")
        sound.clear()

        rule.pressOk()
        letSoundSettle()

        assertEquals(Waveform.SQUARE, stored().waveform)
        assertEquals(1, sound.previewCount)
    }

    @Test
    fun choosingAnotherWaveformAfterwardsPreviewsAgainOncePerChange() {
        show()
        goToWaveform("Square")
        rule.pressOk()
        letSoundSettle()
        rule.pressUp() // the [Sine Triangle] pair is right above [Saw Square]
        if (rule.focusedTexts().firstOrNull() != "Sine") rule.pressLeft()
        rule.assertFocused("Sine")
        sound.clear()

        rule.pressOk()
        letSoundSettle()

        assertEquals(Waveform.SINE, stored().waveform)
        assertEquals(1, sound.previewCount)
    }

    @Test
    fun choosingTheWaveformThatIsAlreadySelectedReplaysThePreviewWithoutChangingIt() {
        show() // Saw selected
        goToWaveform("Saw")
        sound.clear()

        rule.pressOk()
        letSoundSettle()

        assertEquals(Waveform.SAW, stored().waveform)
        assertEquals(1, sound.previewCount)
    }

    @Test
    fun choosingAWaveformWithInterfaceSoundsOffStoresItButStaysSilent() {
        show(LauncherSettings(reducedMotion = true, soundsEnabled = false))
        goToWaveform("Square")
        sound.clear()

        rule.pressOk()
        letSoundSettle()

        assertEquals(Waveform.SQUARE, stored().waveform)
        assertEquals(0, sound.previewCount)
    }

    @Test
    fun turningInterfaceSoundsBackOnPlaysOneConfirmingSelect() {
        show(LauncherSettings(reducedMotion = true, soundsEnabled = false))
        downUntil("Interface sounds")
        sound.clear()

        rule.pressOk()
        letSoundSettle()

        assertEquals(true, stored().soundsEnabled)
        assertEquals(1, sound.selectCount)
        assertEquals(0, sound.previewCount)
    }

    // -----------------------------------------------------------------------------------------------------
    // Screensaver
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun choosingGooglePhotosStoresItAndAppliesItToTheController() {
        val controller = RecordingScreensaver()
        show(screensaver = controller)
        downUntil("Oxyfield")
        rule.pressDown()
        rule.assertFocused("Google Photos")
        sound.clear()

        rule.pressOk()
        rule.waitForIdle()

        assertEquals(Screensaver.GOOGLE_PHOTOS, stored().screensaver)
        assertEquals(listOf(Screensaver.GOOGLE_PHOTOS), controller.applied)
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun startNowWhenTheTvRefusesShowsTheHint() {
        show() // NoOpScreensaverController.startNow() is false
        downUntil("Start now")

        rule.pressOk()
        rule.waitForIdle()

        // The line is the next list item; bring it into the lazy list before looking for it.
        val hint = "This TV won't start it on demand. It will run when idle."
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(hint))
        rule.onNodeWithText(hint).assertExists()
        assertEquals(1, sound.selectCount)
    }

    // -----------------------------------------------------------------------------------------------------
    // Per-app motifs (end of the list)
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun theLongListIsWalkableToTheMotifRowsWithoutEverLosingFocus() {
        show()

        downUntil("Re-roll")

        rule.assertFocused("Re-roll")
    }

    @Test
    fun rerollOnAnAppIncrementsItsOverrideAndPlaysThatAppsLaunch() {
        show()
        downUntil("Re-roll") // the first row is "Files" (apps are sorted by label)
        sound.clear()

        rule.pressOk()
        letSoundSettle()

        val files = apps.app("Files")
        assertEquals(1, storedOverrides()[files.component.packageName]?.rerollCount)
        assertEquals(listOf(files), sound.launches)
    }

    @Test
    fun rerollingTwiceCountsTwiceAndPlaysEachTime() {
        show()
        downUntil("Re-roll")

        rule.pressOk()
        letSoundSettle()
        rule.pressOk()
        letSoundSettle()

        val files = apps.app("Files")
        assertEquals(2, storedOverrides()[files.component.packageName]?.rerollCount)
        assertEquals(2, sound.launches.size)
    }

    @Test
    fun muteOnAnAppStoresMutedAndPlaysSelect() {
        show()
        downUntil("Re-roll")
        rule.pressRight()
        rule.assertFocused("Mute")
        sound.clear()

        rule.pressOk()
        rule.waitForIdle()

        val files = apps.app("Files")
        assertEquals(true, storedOverrides()[files.component.packageName]?.muted)
        assertEquals(1, sound.selectCount)
    }

    @Test
    fun resetClearsTheOverrideAndFocusSurvivesOnTheRow() {
        val files = apps.app("Files")
        show(motifs = mapOf(files.component.packageName to MotifOverride(rerollCount = 3, muted = true)))
        downUntil("Re-roll")
        rule.pressRepeatedly(Key.DirectionRight, 2)
        rule.assertFocused("Reset")

        rule.pressOk()
        rule.waitForIdle()

        assertTrue(storedOverrides().isEmpty())
        rule.assertSingleFocus()
        rule.assertFocused("Re-roll")
    }

    // -----------------------------------------------------------------------------------------------------
    // Back
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun backCallsOnBackAndPlaysTheBackSound() {
        show()
        sound.clear()

        rule.pressBack()

        assertEquals(1, backs)
        assertEquals(1, sound.backCount)
    }

    @Test
    fun backWorksFromDeepInTheMotifList() {
        show()
        downUntil("Re-roll")

        rule.pressBack()

        assertEquals(1, backs)
    }
}

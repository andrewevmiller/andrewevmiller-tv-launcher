package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.sound.Waveform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenLogicTest {

    // ----- dot-grid stepper -----

    @Test
    fun stepMovesByOneTenthExactly() {
        assertEquals(0.8f, DotBrightness.step(0.7f, +1), 0f)
        assertEquals(0.6f, DotBrightness.step(0.7f, -1), 0f)
        assertEquals(0.3f, DotBrightness.step(0.1f, +2), 0f)
    }

    @Test
    fun stepClampsAtBothEnds() {
        assertEquals(1f, DotBrightness.step(1f, +1), 0f)
        assertEquals(0f, DotBrightness.step(0f, -1), 0f)
        assertEquals(1f, DotBrightness.step(0.9f, +5), 0f)
        assertEquals(0f, DotBrightness.step(0.2f, -5), 0f)
        assertEquals(1f, DotBrightness.step(0.5f, Int.MAX_VALUE), 0f)
        assertEquals(0f, DotBrightness.step(0.5f, Int.MIN_VALUE), 0f)
    }

    @Test
    fun tenStepsUpFromZeroLandOnExactlyOne() {
        var alpha = 0f
        repeat(10) { alpha = DotBrightness.step(alpha, +1) }
        // Exact float equality: no drift (naive 0.1f accumulation gives 1.0000001f).
        assertEquals(1f, alpha, 0f)
        repeat(10) { alpha = DotBrightness.step(alpha, -1) }
        assertEquals(0f, alpha, 0f)
    }

    @Test
    fun everyTenthRoundTripsThroughTenths() {
        for (t in 0..10) {
            val alpha = DotBrightness.fromTenths(t)
            assertEquals(t, DotBrightness.toTenths(alpha))
            assertEquals(t / 10f, alpha, 0f)
        }
    }

    @Test
    fun toTenthsRoundsAndClampsStoredValues() {
        assertEquals(3, DotBrightness.toTenths(0.30000001f))
        assertEquals(7, DotBrightness.toTenths(0.74f))
        assertEquals(8, DotBrightness.toTenths(0.75f))
        assertEquals(10, DotBrightness.toTenths(3f))
        assertEquals(0, DotBrightness.toTenths(-2f))
        assertEquals(10, DotBrightness.toTenths(Float.NaN))
        assertEquals(10, DotBrightness.toTenths(Float.POSITIVE_INFINITY))
        assertEquals(0, DotBrightness.toTenths(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun canStepIsFalseOnlyAtTheEnds() {
        assertFalse(DotBrightness.canStep(1f, +1))
        assertTrue(DotBrightness.canStep(1f, -1))
        assertFalse(DotBrightness.canStep(0f, -1))
        assertTrue(DotBrightness.canStep(0f, +1))
        assertTrue(DotBrightness.canStep(0.5f, +1))
        assertTrue(DotBrightness.canStep(0.5f, -1))
    }

    @Test
    fun cycleWrapsFromFullBackToZero() {
        assertEquals(0.1f, DotBrightness.cycle(0f), 0f)
        assertEquals(1f, DotBrightness.cycle(0.9f), 0f)
        assertEquals(0f, DotBrightness.cycle(1f), 0f)
    }

    @Test
    fun labelShowsOneDecimalWithoutLocaleDependence() {
        assertEquals("0.0", DotBrightness.label(0f))
        assertEquals("0.1", DotBrightness.label(0.1f))
        assertEquals("0.7", DotBrightness.label(0.7f))
        assertEquals("1.0", DotBrightness.label(1f))
        assertEquals("1.0", DotBrightness.label(9f))
    }

    @Test
    fun randomStepSequencesMatchAnIntegerModel() {
        val rng = java.util.Random(42)
        var alpha = 1f
        var model = 10
        repeat(500) {
            val delta = rng.nextInt(7) - 3
            alpha = DotBrightness.step(alpha, delta)
            model = (model + delta).coerceIn(0, 10)
            assertEquals(model, DotBrightness.toTenths(alpha))
            assertEquals(model / 10f, alpha, 0f)
        }
    }

    // ----- reducers -----

    @Test
    fun reducersChangeOnlyTheirOwnField() {
        val base = LauncherSettings()
        assertEquals(base.copy(darkMode = false), base.withDarkMode(false))
        assertEquals(base.copy(reducedMotion = true), base.withReducedMotion(true))
        assertEquals(base.copy(soundsEnabled = false), base.withSoundsEnabled(false))
        assertEquals(base.copy(waveform = Waveform.SUPERSAW), base.withWaveform(Waveform.SUPERSAW))
        assertEquals(base.copy(screensaver = Screensaver.GOOGLE_PHOTOS), base.withScreensaver(Screensaver.GOOGLE_PHOTOS))
        assertEquals(base.copy(dotGridAlpha = 0.4f), base.withDotGridAlpha(0.4f))
    }

    @Test
    fun reducersReturnTheSameInstanceWhenNothingChanges() {
        val base = LauncherSettings()
        assertSame(base, base.withDarkMode(true))
        assertSame(base, base.withReducedMotion(false))
        assertSame(base, base.withSoundsEnabled(true))
        assertSame(base, base.withWaveform(Waveform.SAW))
        assertSame(base, base.withScreensaver(Screensaver.OXYFIELD))
        assertSame(base, base.withDotGridAlpha(1f))
        assertSame(base, base.withDotGridStep(+1))
    }

    @Test
    fun dotGridReducersSnapAndClamp() {
        val base = LauncherSettings()
        assertEquals(0.7f, base.withDotGridAlpha(0.74f).dotGridAlpha, 0f)
        assertEquals(1f, base.withDotGridAlpha(3f).dotGridAlpha, 0f)
        assertEquals(0f, base.withDotGridAlpha(-1f).dotGridAlpha, 0f)
        assertEquals(0.9f, base.withDotGridStep(-1).dotGridAlpha, 0f)
        assertEquals(0f, base.withDotGridStep(-50).dotGridAlpha, 0f)
        assertEquals(0.1f, base.withDotGridAlpha(0f).withDotGridCycled().dotGridAlpha, 0f)
        assertEquals(0f, base.withDotGridCycled().dotGridAlpha, 0f)
    }

    @Test
    fun reducersLeaveOtherFieldsAlone() {
        val base = LauncherSettings(
            darkMode = false, reducedMotion = true, soundsEnabled = false,
            waveform = Waveform.PINK_NOISE, screensaver = Screensaver.GOOGLE_PHOTOS, dotGridAlpha = 0.3f,
        )
        assertEquals(base.copy(dotGridAlpha = 0.4f), base.withDotGridStep(+1))
        assertEquals(base.copy(waveform = Waveform.SINE), base.withWaveform(Waveform.SINE))
    }

    @Test
    fun reducersWorkThroughTheRepositoryContract() = runBlocking {
        val repo = FakeSettingsRepository()
        repo.update { it.withWaveform(Waveform.SQUARE) }
        repo.update { it.withDotGridStep(-3) }
        repo.update { it.withDarkMode(false) }
        val s = repo.settings.first()
        assertEquals(Waveform.SQUARE, s.waveform)
        assertEquals(0.7f, s.dotGridAlpha, 0f)
        assertFalse(s.darkMode)
    }

    // ----- waveform names -----

    @Test
    fun waveformOptionsAreExactlySevenInThePlansOrder() {
        assertEquals(7, WaveformOptions.size)
        assertEquals(
            listOf("Sine", "Triangle", "Saw", "Square", "Supersaw", "White noise", "Pink noise"),
            WaveformOptions.map { it.label },
        )
        assertEquals(
            listOf(
                Waveform.SINE, Waveform.TRIANGLE, Waveform.SAW, Waveform.SQUARE,
                Waveform.SUPERSAW, Waveform.WHITE_NOISE, Waveform.PINK_NOISE,
            ),
            WaveformOptions.map { it.waveform },
        )
    }

    @Test
    fun waveformOptionsCoverEveryWaveformOnce() {
        assertEquals(Waveform.values().toSet(), WaveformOptions.map { it.waveform }.toSet())
        assertEquals(WaveformOptions.size, WaveformOptions.map { it.label }.toSet().size)
    }

    @Test
    fun screensaverNamesAreFixed() {
        assertEquals("Oxyfield", Screensaver.OXYFIELD.displayName())
        assertEquals("Google Photos", Screensaver.GOOGLE_PHOTOS.displayName())
        assertTrue(Screensaver.values().all { it.detail().isNotBlank() })
    }

    // ----- when the preview plays -----

    @Test
    fun noPreviewOnFirstObservation() {
        // previous == null: the stored value has just been seen for the first time.
        assertFalse(SoundFeedback.shouldPreview(null, Waveform.SAW, soundsEnabled = true))
        assertFalse(SoundFeedback.shouldPreview(null, Waveform.SUPERSAW, soundsEnabled = true))
    }

    @Test
    fun previewPlaysWhenTheWaveformChanges() {
        assertTrue(SoundFeedback.shouldPreview(Waveform.SAW, Waveform.SQUARE, soundsEnabled = true))
        assertTrue(SoundFeedback.shouldPreview(Waveform.PINK_NOISE, Waveform.SINE, soundsEnabled = true))
    }

    @Test
    fun previewDoesNotPlayWhenNothingChanged() {
        assertFalse(SoundFeedback.shouldPreview(Waveform.SAW, Waveform.SAW, soundsEnabled = true))
    }

    @Test
    fun previewIsSilentWhileInterfaceSoundsAreOff() {
        assertFalse(SoundFeedback.shouldPreview(Waveform.SAW, Waveform.SQUARE, soundsEnabled = false))
    }

    @Test
    fun soundsOnConfirmsOnlyOnARealOffToOnChange() {
        assertTrue(SoundFeedback.shouldConfirmSoundsOn(false, true))
        assertFalse(SoundFeedback.shouldConfirmSoundsOn(null, true))
        assertFalse(SoundFeedback.shouldConfirmSoundsOn(true, true))
        assertFalse(SoundFeedback.shouldConfirmSoundsOn(true, false))
        assertFalse(SoundFeedback.shouldConfirmSoundsOn(false, false))
    }

    @Test
    fun aWaveformChangeFlowPlaysExactlyOnePreviewPerChange() = runBlocking {
        // Mirrors the screen's effect: observe values coming back from the repository in order.
        val repo = FakeSettingsRepository()
        val picks = listOf(Waveform.SQUARE, Waveform.SQUARE, Waveform.SUPERSAW, Waveform.SAW)
        var seen: Waveform? = null
        var previews = 0
        fun observe(s: LauncherSettings) {
            if (SoundFeedback.shouldPreview(seen, s.waveform, s.soundsEnabled)) previews++
            seen = s.waveform
        }
        observe(repo.settings.first()) // baseline, as when the screen opens
        for (w in picks) {
            repo.update { it.withWaveform(w) }
            observe(repo.settings.first())
        }
        // SAW -> SQUARE, (SQUARE again: no change), SQUARE -> SUPERSAW, SUPERSAW -> SAW
        assertEquals(3, previews)
    }

    // ----- screensaver -----

    private class RecordingController(override val canSwitch: Boolean, private val startResult: Boolean = true) :
        ScreensaverController {
        val applied = mutableListOf<Screensaver>()
        var starts = 0
        override fun apply(choice: Screensaver) { if (canSwitch) applied += choice }
        override fun startNow(): Boolean { starts++; return startResult }
    }

    @Test
    fun choosingAScreensaverAppliesItAndStoresIt() = runBlocking {
        val repo = FakeSettingsRepository()
        val controller = RecordingController(canSwitch = true)
        chooseScreensaver(Screensaver.GOOGLE_PHOTOS, repo, controller)
        assertEquals(listOf(Screensaver.GOOGLE_PHOTOS), controller.applied)
        assertEquals(Screensaver.GOOGLE_PHOTOS, repo.settings.first().screensaver)
    }

    @Test
    fun theChoiceIsStoredEvenWhenTheSystemSettingCannotBeWritten() = runBlocking {
        val repo = FakeSettingsRepository()
        val controller = RecordingController(canSwitch = false)
        chooseScreensaver(Screensaver.GOOGLE_PHOTOS, repo, controller)
        assertTrue(controller.applied.isEmpty())
        assertEquals(Screensaver.GOOGLE_PHOTOS, repo.settings.first().screensaver)
    }

    @Test
    fun switchHintShowsOnlyWhenSwitchingIsImpossible() {
        assertEquals("Run scripts/deploy.sh once to allow switching", ScreensaverText.switchHint(false))
        assertNull(ScreensaverText.switchHint(true))
    }

    @Test
    fun startNowMessageAppearsOnlyWhenTheTvRefuses() {
        assertEquals(
            "This TV won't start it on demand. It will run when idle.",
            ScreensaverText.startNowMessage(started = false),
        )
        assertNull(ScreensaverText.startNowMessage(started = true))
        val refusing = RecordingController(canSwitch = true, startResult = false)
        assertEquals(ScreensaverText.START_REFUSED, ScreensaverText.startNowMessage(refusing.startNow()))
        assertEquals(1, refusing.starts)
    }

    // ----- about -----

    @Test
    fun aboutTextMatchesTheRescueInstructions() {
        assertEquals(
            "scripts/rescue.sh restores your previous home screen; " +
                "scripts/rescue.sh --stock brings back Google TV's home.",
            AboutText.RESCUE_HOME,
        )
        assertEquals("Two crashes within a minute hand Home back automatically.", AboutText.CRASH_FALLBACK)
        assertEquals("Version 0.1.0", AboutText.version("0.1.0"))
        assertEquals("Version unknown", AboutText.version(null))
        assertEquals("Version unknown", AboutText.version("  "))
    }
}

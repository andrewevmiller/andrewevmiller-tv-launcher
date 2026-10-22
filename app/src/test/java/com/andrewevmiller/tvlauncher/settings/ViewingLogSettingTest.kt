package com.andrewevmiller.tvlauncher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Viewing log switch: on by default, read from old stores that never had it, and round-trips both ways. */
class ViewingLogSettingTest {

    @Test
    fun viewingLogIsOnByDefault() {
        assertTrue(LauncherSettings().viewingLogEnabled)
        assertTrue(SettingsCodec.decode(null, null, null, null, null, null).viewingLogEnabled)
    }

    @Test
    fun anOldStoreWithNoKeyLoadsAsOn() {
        // Stores from before the log existed have no viewing_log_enabled key: the decode sees null.
        val old = SettingsCodec.decode(
            darkMode = false,
            reducedMotion = true,
            soundsEnabled = false,
            waveform = "SQUARE",
            screensaver = "GOOGLE_PHOTOS",
            dotGridAlpha = 0.5f,
            sleepMinutes = 45,
        )
        assertTrue(old.viewingLogEnabled)
        assertEquals(false, old.darkMode)
        assertEquals(45, old.sleepMinutes)
    }

    @Test
    fun storedFalseDecodesAsOff() {
        assertFalse(SettingsCodec.decode(null, null, null, null, null, null, viewingLogEnabled = false).viewingLogEnabled)
    }

    @Test
    fun storedTrueDecodesAsOn() {
        assertTrue(SettingsCodec.decode(null, null, null, null, null, null, viewingLogEnabled = true).viewingLogEnabled)
    }

    @Test
    fun bothValuesRoundTripThroughTheCodec() {
        for (on in listOf(true, false)) {
            val stored = LauncherSettings(viewingLogEnabled = on)
            val decoded = SettingsCodec.decode(
                darkMode = stored.darkMode,
                reducedMotion = stored.reducedMotion,
                soundsEnabled = stored.soundsEnabled,
                waveform = SettingsCodec.encodeWaveform(stored.waveform),
                screensaver = SettingsCodec.encodeScreensaver(stored.screensaver),
                dotGridAlpha = stored.dotGridAlpha,
                sleepMinutes = stored.sleepMinutes,
                viewingLogEnabled = stored.viewingLogEnabled,
            )
            assertEquals(stored, decoded)
        }
    }

    @Test
    fun turningTheLogOffDoesNotChangeTheOtherSettings() {
        val base = SettingsCodec.decode(false, true, false, "SINE", "GOOGLE_PHOTOS", 0.3f, 90)
        val off = base.copy(viewingLogEnabled = false)
        assertEquals(base.copy(viewingLogEnabled = true), off.copy(viewingLogEnabled = true))
    }
}

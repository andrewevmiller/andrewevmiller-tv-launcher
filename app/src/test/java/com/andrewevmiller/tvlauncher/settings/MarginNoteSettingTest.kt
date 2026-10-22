package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.marginnote.MarginNoteRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** The margin note in the settings store: empty by default, old stores load as empty, bad input is cleaned. */
class MarginNoteSettingTest {

    private fun decodeWithNote(note: String?): LauncherSettings =
        SettingsCodec.decode(null, null, null, null, null, null, marginNote = note)

    @Test
    fun theNoteIsEmptyByDefault() {
        assertEquals("", LauncherSettings().marginNote)
        assertEquals("", SettingsCodec.decode(null, null, null, null, null, null).marginNote)
    }

    @Test
    fun anOldStoreWithNoKeyLoadsWithAnEmptyNote() {
        // Stores from before the note existed have no margin_note key: the decode sees null.
        val old = SettingsCodec.decode(
            darkMode = false,
            reducedMotion = true,
            soundsEnabled = false,
            waveform = "SQUARE",
            screensaver = "GOOGLE_PHOTOS",
            dotGridAlpha = 0.5f,
            sleepMinutes = 45,
            viewingLogEnabled = false,
        )
        assertEquals("", old.marginNote)
        assertEquals(false, old.viewingLogEnabled)
        assertEquals(45, old.sleepMinutes)
    }

    @Test
    fun aStoredNoteRoundTrips() {
        val stored = LauncherSettings(marginNote = "finish S2 of Severance")
        val decoded = SettingsCodec.decode(
            darkMode = stored.darkMode,
            reducedMotion = stored.reducedMotion,
            soundsEnabled = stored.soundsEnabled,
            waveform = SettingsCodec.encodeWaveform(stored.waveform),
            screensaver = SettingsCodec.encodeScreensaver(stored.screensaver),
            dotGridAlpha = stored.dotGridAlpha,
            sleepMinutes = stored.sleepMinutes,
            viewingLogEnabled = stored.viewingLogEnabled,
            marginNote = stored.marginNote,
        )
        assertEquals(stored, decoded)
    }

    @Test
    fun anEmptyNoteRoundTripsAsEmpty() {
        assertEquals("", decodeWithNote(SettingsCodec.encodeMarginNote("")).marginNote)
    }

    @Test
    fun aNoteWithControlCharactersIsCleanedOnDecode() {
        assertEquals("finishS2", decodeWithNote("finish\nS2").marginNote)
        assertEquals("ab", decodeWithNote("a\tb").marginNote)
    }

    @Test
    fun aLongNoteIsClampedToEightyOnDecode() {
        val long = "x".repeat(120)
        assertEquals("x".repeat(80), decodeWithNote(long).marginNote)
    }

    @Test
    fun badInputFallsBackToEmpty() {
        for (raw in listOf(null, "", "   ", "\n\n", "\u0000\u0001", "\t \r\n")) {
            assertEquals("raw=[$raw]", "", decodeWithNote(raw).marginNote)
        }
    }

    @Test
    fun encodeAndDecodeAgreeOnEverySanitisedForm() {
        val inputs = listOf("  spaced  ", "two\nlines", "x".repeat(81), "😀".repeat(81), "plain")
        for (raw in inputs) {
            val stored = SettingsCodec.encodeMarginNote(raw)
            assertEquals(MarginNoteRules.sanitize(raw), stored)
            assertEquals("raw=$raw", stored, decodeWithNote(stored).marginNote)
        }
    }

    @Test
    fun changingTheNoteDoesNotChangeTheOtherSettings() {
        val base = SettingsCodec.decode(false, true, false, "SINE", "GOOGLE_PHOTOS", 0.3f, 90, false)
        val noted = base.copy(marginNote = "reading")
        assertEquals(base, noted.copy(marginNote = ""))
    }
}

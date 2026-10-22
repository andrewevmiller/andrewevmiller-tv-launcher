package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.sound.Waveform
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun allNullDecodesToDefaults() {
        assertEquals(LauncherSettings(), SettingsCodec.decode(null, null, null, null, null, null))
    }

    @Test
    fun defaultsMatchTheDocumentedValues() {
        val s = SettingsCodec.decode(null, null, null, null, null, null)
        assertEquals(true, s.darkMode)
        assertEquals(false, s.reducedMotion)
        assertEquals(true, s.soundsEnabled)
        assertEquals(Waveform.SAW, s.waveform)
        assertEquals(Screensaver.OXYFIELD, s.screensaver)
        assertEquals(1f, s.dotGridAlpha, 0f)
    }

    @Test
    fun everyWaveformRoundTrips() {
        for (w in Waveform.entries) {
            assertEquals(w, SettingsCodec.decodeWaveform(SettingsCodec.encodeWaveform(w)))
        }
    }

    @Test
    fun everyScreensaverRoundTrips() {
        for (s in Screensaver.entries) {
            assertEquals(s, SettingsCodec.decodeScreensaver(SettingsCodec.encodeScreensaver(s)))
        }
    }

    @Test
    fun enumsAreStoredByName() {
        assertEquals("SUPERSAW", SettingsCodec.encodeWaveform(Waveform.SUPERSAW))
        assertEquals("GOOGLE_PHOTOS", SettingsCodec.encodeScreensaver(Screensaver.GOOGLE_PHOTOS))
    }

    @Test
    fun enumDecodingIsCaseInsensitive() {
        assertEquals(Waveform.SQUARE, SettingsCodec.decodeWaveform("square"))
        assertEquals(Waveform.PINK_NOISE, SettingsCodec.decodeWaveform("Pink_Noise"))
        assertEquals(Screensaver.GOOGLE_PHOTOS, SettingsCodec.decodeScreensaver("google_photos"))
    }

    @Test
    fun garbageWaveformFallsBackToDefault() {
        for (raw in listOf("", "sawtooth", "SAW2", "null", "\u0000", "saw\n")) {
            assertEquals("raw=$raw", Waveform.SAW, SettingsCodec.decodeWaveform(raw))
        }
        assertEquals(Waveform.SAW, SettingsCodec.decodeWaveform(null))
    }

    @Test
    fun garbageScreensaverFallsBackToDefault() {
        for (raw in listOf("", "google", "GOOGLE PHOTOS", "photos", "{}", "OXYFIELD_V2")) {
            assertEquals("raw=$raw", Screensaver.OXYFIELD, SettingsCodec.decodeScreensaver(raw))
        }
        assertEquals(Screensaver.OXYFIELD, SettingsCodec.decodeScreensaver(null))
    }

    @Test
    fun renamedOrRemovedConstantsDecodeToDefault() {
        // A constant renamed or removed in a later build must not throw; it reads as the default.
        assertEquals(Waveform.SAW, SettingsCodec.decodeWaveform("TRIANGLE_OLD"))
        assertEquals(Waveform.SAW, SettingsCodec.decodeWaveform("SUPER_SAW"))
        assertEquals(Screensaver.OXYFIELD, SettingsCodec.decodeScreensaver("DREAM_PHOTOS"))
    }

    @Test
    fun garbageInEveryFieldDecodesToDefaults() {
        assertEquals(
            LauncherSettings(),
            SettingsCodec.decode(null, null, null, "nope", "nope", Float.NaN),
        )
    }

    @Test
    fun alphaInsideRangeIsUnchanged() {
        assertEquals(0.25f, SettingsCodec.clampDotGridAlpha(0.25f), 0f)
        assertEquals(0.5f, SettingsCodec.clampDotGridAlpha(0.5f), 0f)
    }

    @Test
    fun alphaExactlyZeroAndOneIsKept() {
        assertEquals(0f, SettingsCodec.clampDotGridAlpha(0f), 0f)
        assertEquals(1f, SettingsCodec.clampDotGridAlpha(1f), 0f)
    }

    @Test
    fun negativeAlphaClampsToZero() {
        assertEquals(0f, SettingsCodec.clampDotGridAlpha(-0.5f), 0f)
        assertEquals(0f, SettingsCodec.clampDotGridAlpha(-Float.MAX_VALUE), 0f)
        assertEquals(0f, SettingsCodec.clampDotGridAlpha(-0f), 0f)
    }

    @Test
    fun alphaAboveOneClampsToOne() {
        assertEquals(1f, SettingsCodec.clampDotGridAlpha(1.5f), 0f)
        assertEquals(1f, SettingsCodec.clampDotGridAlpha(Float.MAX_VALUE), 0f)
    }

    @Test
    fun nonFiniteAlphaBecomesOne() {
        assertEquals(1f, SettingsCodec.clampDotGridAlpha(Float.NaN), 0f)
        assertEquals(1f, SettingsCodec.clampDotGridAlpha(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, SettingsCodec.clampDotGridAlpha(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun nullAlphaUsesDefault() {
        assertEquals(1f, SettingsCodec.clampDotGridAlpha(null), 0f)
    }

    @Test
    fun decodeAssemblesEveryField() {
        val s = SettingsCodec.decode(
            darkMode = false,
            reducedMotion = true,
            soundsEnabled = false,
            waveform = "SUPERSAW",
            screensaver = "GOOGLE_PHOTOS",
            dotGridAlpha = 0.4f,
        )
        assertEquals(
            LauncherSettings(
                darkMode = false,
                reducedMotion = true,
                soundsEnabled = false,
                waveform = Waveform.SUPERSAW,
                screensaver = Screensaver.GOOGLE_PHOTOS,
                dotGridAlpha = 0.4f,
            ),
            s,
        )
    }

    @Test
    fun decodeClampsAlpha() {
        assertEquals(1f, SettingsCodec.decode(null, null, null, null, null, 3f).dotGridAlpha, 0f)
        assertEquals(0f, SettingsCodec.decode(null, null, null, null, null, -1f).dotGridAlpha, 0f)
    }

    @Test
    fun everyWaveformAndScreensaverRoundTripThroughDecode() {
        for (w in Waveform.entries) {
            for (s in Screensaver.entries) {
                val decoded = SettingsCodec.decode(
                    darkMode = true,
                    reducedMotion = false,
                    soundsEnabled = true,
                    waveform = SettingsCodec.encodeWaveform(w),
                    screensaver = SettingsCodec.encodeScreensaver(s),
                    dotGridAlpha = 0.5f,
                )
                assertEquals(w, decoded.waveform)
                assertEquals(s, decoded.screensaver)
            }
        }
    }
}

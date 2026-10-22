package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.marginnote.MarginNoteRules
import com.andrewevmiller.tvlauncher.sleep.SleepPresets
import com.andrewevmiller.tvlauncher.sound.Waveform

/**
 * Pure codec from the raw stored preference values to [LauncherSettings].
 *
 * Each setting is stored as its own typed value (see [DataStoreSettingsRepository]). Every decode is
 * total: a null, unknown, or renamed value falls back to the [LauncherSettings] default and never throws,
 * so an old or hand-edited store cannot crash the launcher. Enums are stored by `name` and matched
 * case-insensitively.
 */
object SettingsCodec {
    private val defaults = LauncherSettings()

    fun decode(
        darkMode: Boolean?,
        reducedMotion: Boolean?,
        soundsEnabled: Boolean?,
        waveform: String?,
        screensaver: String?,
        dotGridAlpha: Float?,
        sleepMinutes: Int? = null,
        viewingLogEnabled: Boolean? = null,
        marginNote: String? = null,
    ): LauncherSettings = LauncherSettings(
        darkMode = darkMode ?: defaults.darkMode,
        reducedMotion = reducedMotion ?: defaults.reducedMotion,
        soundsEnabled = soundsEnabled ?: defaults.soundsEnabled,
        waveform = decodeWaveform(waveform),
        screensaver = decodeScreensaver(screensaver),
        dotGridAlpha = clampDotGridAlpha(dotGridAlpha),
        sleepMinutes = SleepPresets.sanitize(sleepMinutes),
        viewingLogEnabled = viewingLogEnabled ?: defaults.viewingLogEnabled,
        marginNote = encodeMarginNote(marginNote),
    )

    /**
     * The margin note as stored and shown: sanitised (no control characters, trimmed, at most 80
     * characters). Null, junk, or a note that is only spaces gives "". Used on decode and on encode.
     */
    fun encodeMarginNote(raw: String?): String = MarginNoteRules.sanitize(raw)

    fun encodeWaveform(w: Waveform): String = w.name

    fun decodeWaveform(raw: String?): Waveform = decodeEnum(raw, Waveform.entries, defaults.waveform)

    fun encodeScreensaver(s: Screensaver): String = s.name

    fun decodeScreensaver(raw: String?): Screensaver = decodeEnum(raw, Screensaver.entries, defaults.screensaver)

    /**
     * Clamps the dot-grid alpha multiplier to 0f..1f. Null falls back to the default; NaN and both
     * infinities become 1f, since a non-finite alpha has no meaning. -0f is normalized to 0f.
     */
    fun clampDotGridAlpha(v: Float?): Float = when {
        v == null -> defaults.dotGridAlpha
        !v.isFinite() -> 1f
        v <= 0f -> 0f
        v >= 1f -> 1f
        else -> v
    }

    /** Case-insensitive match on the constant's name; anything else (including a removed constant) gives [fallback]. */
    private fun <E : Enum<E>> decodeEnum(raw: String?, constants: List<E>, fallback: E): E {
        if (raw == null) return fallback
        return constants.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback
    }
}

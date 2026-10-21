package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.sound.Waveform
import kotlin.math.roundToInt

/*
 * Pure state-change logic behind SettingsScreen. No Android or Compose imports on purpose, so
 * `gradle -p tools/purecheck test` compiles and tests all of it in the cloud. The screen itself
 * (SettingsScreen.kt, SettingsRows.kt) only wires these into D-pad rows.
 */

// ---------------------------------------------------------------------------------------------
// Dot-grid brightness stepper
// ---------------------------------------------------------------------------------------------

/**
 * Arithmetic for the dot-grid brightness stepper. The setting is a Float in 0f..1f, but all
 * stepping happens in integer tenths (0..10) so repeated left/right presses never accumulate
 * floating-point drift (0.1f + 0.2f style errors).
 */
object DotBrightness {
    const val MIN_TENTHS = 0
    const val MAX_TENTHS = 10

    /** The nearest tenth, clamped to 0..10. NaN (a corrupt store) reads as full brightness, the default. */
    fun toTenths(alpha: Float): Int {
        if (alpha.isNaN()) return MAX_TENTHS
        return (alpha * 10f).roundToInt().coerceIn(MIN_TENTHS, MAX_TENTHS)
    }

    fun fromTenths(tenths: Int): Float = tenths.coerceIn(MIN_TENTHS, MAX_TENTHS) / 10f

    /** [alpha] snapped to a whole tenth inside 0f..1f. */
    fun snap(alpha: Float): Float = fromTenths(toTenths(alpha))

    /** [alpha] moved by [deltaTenths] tenths, clamped. */
    fun step(alpha: Float, deltaTenths: Int): Float {
        // Widen to Long so a huge delta cannot overflow before it is clamped.
        val next = (toTenths(alpha).toLong() + deltaTenths.toLong())
            .coerceIn(MIN_TENTHS.toLong(), MAX_TENTHS.toLong())
        return fromTenths(next.toInt())
    }

    /** False when a step of [deltaTenths] would leave the value unchanged (the stepper is at an end). */
    fun canStep(alpha: Float, deltaTenths: Int): Boolean = toTenths(step(alpha, deltaTenths)) != toTenths(alpha)

    /** One tenth up, wrapping from full back to 0. Bound to OK on the stepper row. */
    fun cycle(alpha: Float): Float {
        val t = toTenths(alpha)
        return fromTenths(if (t >= MAX_TENTHS) MIN_TENTHS else t + 1)
    }

    /** Mono readout, one decimal: `0.7`, `1.0`, `0.0`. Built from integers, so it ignores the device locale. */
    fun label(alpha: Float): String {
        val t = toTenths(alpha)
        return "${t / 10}.${t % 10}"
    }
}

// ---------------------------------------------------------------------------------------------
// Settings reducers. Each returns `this` when nothing changes, so a no-op never rewrites the store.
// ---------------------------------------------------------------------------------------------

fun LauncherSettings.withDarkMode(dark: Boolean): LauncherSettings =
    if (darkMode == dark) this else copy(darkMode = dark)

fun LauncherSettings.withReducedMotion(reduced: Boolean): LauncherSettings =
    if (reducedMotion == reduced) this else copy(reducedMotion = reduced)

fun LauncherSettings.withSoundsEnabled(enabled: Boolean): LauncherSettings =
    if (soundsEnabled == enabled) this else copy(soundsEnabled = enabled)

fun LauncherSettings.withWaveform(waveform: Waveform): LauncherSettings =
    if (this.waveform == waveform) this else copy(waveform = waveform)

fun LauncherSettings.withScreensaver(screensaver: Screensaver): LauncherSettings =
    if (this.screensaver == screensaver) this else copy(screensaver = screensaver)

/** Stores [alpha] snapped to a whole tenth in 0f..1f. */
fun LauncherSettings.withDotGridAlpha(alpha: Float): LauncherSettings {
    val snapped = DotBrightness.snap(alpha)
    return if (dotGridAlpha == snapped) this else copy(dotGridAlpha = snapped)
}

/** Moves the dot-grid brightness by [deltaTenths] tenths (negative = dimmer), clamped to 0f..1f. */
fun LauncherSettings.withDotGridStep(deltaTenths: Int): LauncherSettings =
    withDotGridAlpha(DotBrightness.step(dotGridAlpha, deltaTenths))

/** OK on the stepper row: one tenth up, wrapping back to 0 after full brightness. */
fun LauncherSettings.withDotGridCycled(): LauncherSettings = withDotGridAlpha(DotBrightness.cycle(dotGridAlpha))

// ---------------------------------------------------------------------------------------------
// Waveform picker content
// ---------------------------------------------------------------------------------------------

data class WaveformOption(val waveform: Waveform, val label: String)

/** Name shown in the picker. Exhaustive, so a new [Waveform] cannot ship without a label. */
fun Waveform.displayName(): String = when (this) {
    Waveform.SINE -> "Sine"
    Waveform.TRIANGLE -> "Triangle"
    Waveform.SAW -> "Saw"
    Waveform.SQUARE -> "Square"
    Waveform.SUPERSAW -> "Supersaw"
    Waveform.WHITE_NOISE -> "White noise"
    Waveform.PINK_NOISE -> "Pink noise"
}

/** All seven waveforms in the plan's order (the enum's order), with their display names. */
val WaveformOptions: List<WaveformOption> = Waveform.values().map { WaveformOption(it, it.displayName()) }

fun Screensaver.displayName(): String = when (this) {
    Screensaver.OXYFIELD -> "Oxyfield"
    Screensaver.GOOGLE_PHOTOS -> "Google Photos"
}

fun Screensaver.detail(): String = when (this) {
    Screensaver.OXYFIELD -> "Our dot-field animation"
    Screensaver.GOOGLE_PHOTOS -> "Google TV ambient mode, your albums"
}

// ---------------------------------------------------------------------------------------------
// When feedback sounds play
// ---------------------------------------------------------------------------------------------

object SoundFeedback {
    /**
     * Pause between a setting being written and the sound that demonstrates it. The sound engine re-renders
     * its cached sounds when it observes the new waveform / override, so playing in the same instant as the
     * write could still sound the old settings.
     */
    const val SETTLE_MS = 150L

    /**
     * The preview plays when the waveform really changed to [current] while the screen was open.
     *
     * [previous] is the first waveform seen after settings finished loading, or the one before this change.
     * It is null until the stored value has been observed once, so opening the screen (or the stored value
     * arriving after the default) never plays a preview. Nothing plays while interface sounds are off.
     */
    fun shouldPreview(previous: Waveform?, current: Waveform, soundsEnabled: Boolean): Boolean =
        soundsEnabled && previous != null && previous != current

    /** Turning interface sounds on plays one confirming select, like the mockup's toggle. */
    fun shouldConfirmSoundsOn(previous: Boolean?, current: Boolean): Boolean = previous == false && current
}

// ---------------------------------------------------------------------------------------------
// Screensaver
// ---------------------------------------------------------------------------------------------

object ScreensaverText {
    const val CANNOT_SWITCH = "Run scripts/deploy.sh once to allow switching"
    const val START_REFUSED = "This TV won't start it on demand. It will run when idle."

    /** Muted hint under the choice; null when switching is possible. */
    fun switchHint(canSwitch: Boolean): String? = if (canSwitch) null else CANNOT_SWITCH

    /** Message after pressing Start now; null when the device accepted the start. */
    fun startNowMessage(started: Boolean): String? = if (started) null else START_REFUSED
}

/**
 * Applies the owner's screensaver choice: writes the system setting (a no-op when the controller cannot
 * switch) and always stores the choice, so it survives until the permission is granted.
 */
suspend fun chooseScreensaver(
    choice: Screensaver,
    settings: SettingsRepository,
    controller: ScreensaverController,
) {
    controller.apply(choice)
    settings.update { it.withScreensaver(choice) }
}

// ---------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------

object AboutText {
    const val RESCUE_HOME = "scripts/rescue.sh restores your previous home screen; " +
        "scripts/rescue.sh --stock brings back Google TV's home."
    const val CRASH_FALLBACK = "Two crashes within a minute hand Home back automatically."

    fun version(versionName: String?): String =
        if (versionName.isNullOrBlank()) "Version unknown" else "Version $versionName"
}

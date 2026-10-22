package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.sleep.SleepPresets
import com.andrewevmiller.tvlauncher.sound.Waveform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Screensaver {
    /** Our own dot-field animation (`OxyfieldDreamService`). */
    OXYFIELD,

    /** Google TV ambient mode, which shows the owner's Google Photos albums. */
    GOOGLE_PHOTOS,
}

data class LauncherSettings(
    val darkMode: Boolean = true,
    val reducedMotion: Boolean = false,
    val soundsEnabled: Boolean = true,
    val waveform: Waveform = Waveform.SAW,
    val screensaver: Screensaver = Screensaver.OXYFIELD,
    /** Multiplier on the dot grid's alpha; panels crush near-black, so this is tuned on the TV. 0f..1f. */
    val dotGridAlpha: Float = 1f,
    /** The sleep timer's default length in minutes; always one of [SleepPresets.minutes]. */
    val sleepMinutes: Int = SleepPresets.DEFAULT_MINUTES,
    /** Whether the Viewing log records what is in front. Nothing is read or written while this is off. */
    val viewingLogEnabled: Boolean = true,
    /** The owner's margin note: one line, at most 80 characters, no control characters. Empty means none. */
    val marginNote: String = "",
)

/** Persisted user settings. Every change is observable, so the UI updates without a restart. */
interface SettingsRepository {
    val settings: Flow<LauncherSettings>
    suspend fun update(transform: (LauncherSettings) -> LauncherSettings)
}

/** In-memory implementation for previews and tests. */
class FakeSettingsRepository(initial: LauncherSettings = LauncherSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<LauncherSettings> = state
    override suspend fun update(transform: (LauncherSettings) -> LauncherSettings) = state.update(transform)
}

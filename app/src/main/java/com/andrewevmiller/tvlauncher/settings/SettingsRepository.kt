package com.andrewevmiller.tvlauncher.settings

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

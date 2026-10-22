package com.andrewevmiller.tvlauncher.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.andrewevmiller.tvlauncher.sleep.SleepPresets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "field_settings")

private object SettingsKeys {
    val darkMode = booleanPreferencesKey("dark_mode")
    val reducedMotion = booleanPreferencesKey("reduced_motion")
    val soundsEnabled = booleanPreferencesKey("sounds_enabled")
    val waveform = stringPreferencesKey("waveform")
    val screensaver = stringPreferencesKey("screensaver")
    val dotGridAlpha = floatPreferencesKey("dot_grid_alpha")
    val sleepMinutes = intPreferencesKey("sleep_minutes")
    val viewingLogEnabled = booleanPreferencesKey("viewing_log_enabled")
}

private fun Preferences.toSettings(): LauncherSettings = SettingsCodec.decode(
    darkMode = this[SettingsKeys.darkMode],
    reducedMotion = this[SettingsKeys.reducedMotion],
    soundsEnabled = this[SettingsKeys.soundsEnabled],
    waveform = this[SettingsKeys.waveform],
    screensaver = this[SettingsKeys.screensaver],
    dotGridAlpha = this[SettingsKeys.dotGridAlpha],
    sleepMinutes = this[SettingsKeys.sleepMinutes],
    viewingLogEnabled = this[SettingsKeys.viewingLogEnabled],
)

/**
 * [SettingsRepository] backed by Preferences DataStore. Each setting is its own typed key. Reads and
 * writes go through [SettingsCodec], so a missing, unknown, or corrupt value reads as its default.
 */
class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val store: DataStore<Preferences> = context.applicationContext.settingsDataStore

    override val settings: Flow<LauncherSettings> = store.data
        .catch { cause ->
            // An unreadable store emits defaults instead of failing the collector.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (LauncherSettings) -> LauncherSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[SettingsKeys.darkMode] = next.darkMode
            prefs[SettingsKeys.reducedMotion] = next.reducedMotion
            prefs[SettingsKeys.soundsEnabled] = next.soundsEnabled
            prefs[SettingsKeys.waveform] = SettingsCodec.encodeWaveform(next.waveform)
            prefs[SettingsKeys.screensaver] = SettingsCodec.encodeScreensaver(next.screensaver)
            prefs[SettingsKeys.dotGridAlpha] = SettingsCodec.clampDotGridAlpha(next.dotGridAlpha)
            prefs[SettingsKeys.sleepMinutes] = SleepPresets.sanitize(next.sleepMinutes)
            prefs[SettingsKeys.viewingLogEnabled] = next.viewingLogEnabled
        }
    }
}

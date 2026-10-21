package com.andrewevmiller.tvlauncher.sound

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.motifOverridesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "field_motif_overrides",
    // A corrupted file is replaced with empty preferences: no overrides, and later writes succeed.
    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() },
)

private val OVERRIDES_KEY = stringPreferencesKey("overrides")

/**
 * [MotifOverrides] backed by Preferences DataStore (file `field_motif_overrides`). All overrides live in one
 * string value in the [MotifOverridesCodec] line format, so a re-roll is a single atomic edit. Anything unreadable
 * reads as "no overrides".
 */
class DataStoreMotifOverrides(context: Context) : MotifOverrides {

    private val store: DataStore<Preferences> = context.applicationContext.motifOverridesDataStore

    override val overrides: Flow<Map<String, MotifOverride>> = store.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> MotifOverridesCodec.decode(prefs[OVERRIDES_KEY]) }
        .distinctUntilChanged()

    override suspend fun reroll(packageName: String) = edit { MotifOverridesCodec.reroll(it, packageName) }

    override suspend fun setMuted(packageName: String, muted: Boolean) =
        edit { MotifOverridesCodec.setMuted(it, packageName, muted) }

    override suspend fun reset(packageName: String) = edit { MotifOverridesCodec.reset(it, packageName) }

    private suspend fun edit(transform: (Map<String, MotifOverride>) -> Map<String, MotifOverride>) {
        store.edit { prefs ->
            val next = transform(MotifOverridesCodec.decode(prefs[OVERRIDES_KEY]))
            if (next.isEmpty()) {
                prefs.remove(OVERRIDES_KEY)
            } else {
                prefs[OVERRIDES_KEY] = MotifOverridesCodec.encode(next)
            }
        }
    }
}

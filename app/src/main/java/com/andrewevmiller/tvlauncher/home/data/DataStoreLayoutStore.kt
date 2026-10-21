package com.andrewevmiller.tvlauncher.home.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.fieldLayoutDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "field_layout",
    // A corrupted file is replaced with empty preferences, so reads yield the default layout and
    // later writes succeed instead of failing on every update.
    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() },
)

private val PINNED_KEY = stringPreferencesKey("pinned")
private val HIDDEN_KEY = stringPreferencesKey("hidden")
private val SEEDED_KEY = booleanPreferencesKey("seeded")

/**
 * [LayoutStore] backed by Preferences DataStore (file `field_layout`). Pinned and hidden are stored
 * as [LayoutCodec] text; `seeded` is a separate boolean. Any read IOException that isn't handled by
 * the corruption handler yields the default [FieldLayout] rather than crashing the launcher.
 */
class DataStoreLayoutStore(context: Context) : LayoutStore {

    private val dataStore: DataStore<Preferences> = context.applicationContext.fieldLayoutDataStore

    override val layout: Flow<FieldLayout> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toLayout() }

    override suspend fun update(transform: (FieldLayout) -> FieldLayout) {
        dataStore.edit { prefs ->
            val next = LayoutCodec.normalize(transform(prefs.toLayout()))
            prefs[PINNED_KEY] = LayoutCodec.encodePinned(next.pinned)
            prefs[HIDDEN_KEY] = LayoutCodec.encodeHidden(next.hidden)
            prefs[SEEDED_KEY] = next.seeded
        }
    }
}

private fun Preferences.toLayout(): FieldLayout = LayoutCodec.normalize(
    FieldLayout(
        pinned = LayoutCodec.decodePinned(this[PINNED_KEY]),
        hidden = LayoutCodec.decodeHidden(this[HIDDEN_KEY]),
        seeded = this[SEEDED_KEY] ?: false,
    ),
)

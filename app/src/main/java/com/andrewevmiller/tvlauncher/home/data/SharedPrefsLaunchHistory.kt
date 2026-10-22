package com.andrewevmiller.tvlauncher.home.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [LaunchHistory] kept in SharedPreferences ("field_history"); the log is tiny, so it is read once and written on each launch. */
class SharedPrefsLaunchHistory(context: Context) : LaunchHistory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(LaunchLog.decode(prefs.getString(KEY, null)))

    override val entries: Flow<List<LaunchEntry>> = state

    override fun record(entry: LaunchEntry) {
        val next = LaunchLog.record(state.value, entry)
        state.value = next
        prefs.edit().putString(KEY, LaunchLog.encode(next)).apply()
    }

    private companion object {
        const val FILE = "field_history"
        const val KEY = "log"
    }
}

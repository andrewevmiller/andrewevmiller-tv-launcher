package com.andrewevmiller.tvlauncher.home.data

import android.content.Context

/** One launch from this launcher: which package, and when (epoch millis). */
data class LaunchRecord(val packageName: String, val at: Long)

/**
 * The launcher's own record of what was opened, newest first, kept in SharedPreferences.
 * Android does not let a launcher read other apps' history, so this is what feeds the feature
 * band and the Recent row.
 */
class LaunchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("launch_history", Context.MODE_PRIVATE)

    fun entries(): List<LaunchRecord> =
        (prefs.getString(KEY, "") ?: "").split(';').mapNotNull { row ->
            val parts = row.split('|')
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && at != null) LaunchRecord(parts[0], at) else null
        }

    /** Records a launch; an app appears once, at its latest time. */
    fun record(packageName: String, at: Long = System.currentTimeMillis()): List<LaunchRecord> {
        val next = (listOf(LaunchRecord(packageName, at)) + entries().filter { it.packageName != packageName })
            .take(MAX)
        prefs.edit().putString(KEY, next.joinToString(";") { "${it.packageName}|${it.at}" }).apply()
        return next
    }

    private companion object {
        const val KEY = "entries"
        const val MAX = 20
    }
}

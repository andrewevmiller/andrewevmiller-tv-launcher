package com.andrewevmiller.tvlauncher.sleep

/** The lengths the sleep timer offers, and the limits around them. Pure so settings and UI share one list. */
object SleepPresets {
    /** Minutes, in the order the picker shows them. */
    val minutes: List<Int> = listOf(15, 30, 45, 60, 90, 120)

    const val DEFAULT_MINUTES = 45

    /** What "extend" adds to a running timer. */
    const val EXTEND_MINUTES = 15

    /** A running timer never goes past this many minutes from now, however often it is extended. */
    const val MAX_MINUTES = 240

    /** [minutes] if it is one of the presets, else the default. Used for anything read back from storage. */
    fun sanitize(stored: Int?): Int = if (stored != null && stored in minutes) stored else DEFAULT_MINUTES
}

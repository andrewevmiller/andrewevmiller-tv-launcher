package com.andrewevmiller.tvlauncher.home

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The header timestamp: `2026-10-21 · 19:42:07 PDT`, or `2026-10-21 · 7:42:07 PM PDT` on a 12-hour TV.
 * The zone is the short name of whatever zone the TV is set to. Pure so it is unit-tested.
 */
object ClockFormat {
    fun pattern(is24Hour: Boolean): String =
        if (is24Hour) "yyyy-MM-dd · HH:mm:ss z" else "yyyy-MM-dd · h:mm:ss a z"

    fun format(now: ZonedDateTime, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(pattern(is24Hour), locale).format(now)

    /** Milliseconds to sleep so the next tick lands on the top of the next second (always 1..1000). */
    fun millisUntilNextSecond(nanoOfSecond: Int): Long =
        (1000L - nanoOfSecond / 1_000_000L).coerceIn(1L, 1000L)
}

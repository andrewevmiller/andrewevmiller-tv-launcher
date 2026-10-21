package com.andrewevmiller.tvlauncher.ambient

/** Pure text for the screensaver clock (the Android layer supplies the calendar fields). */
object OxyfieldClockText {
    private val WEEKDAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /**
     * How far past a whole second or minute a timer is aimed, so it lands just after the boundary and never a
     * hair before it (a timer that fires early would draw the old value and wait a whole period to fix it).
     */
    const val TICK_SLACK_MILLIS = 5L

    /** A minute tick that arrives within this long before the boundary is treated as early (see [tickLandedEarly]). */
    const val EARLY_TICK_WINDOW_MILLIS = 1_000L

    /** "19:42" in 24-hour mode, "7:42 PM" in 12-hour mode. The sleep screen uses this: it shows no seconds. */
    fun time(hour24: Int, minute: Int, is24Hour: Boolean): String {
        val h = hour24.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val mm = m.toString().padStart(2, '0')
        if (is24Hour) return "${h.toString().padStart(2, '0')}:$mm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:$mm ${if (h < 12) "AM" else "PM"}"
    }

    /**
     * "19:42:07" in 24-hour mode, "7:42:07 PM" in 12-hour mode. The normal screensaver uses this. A leap second
     * (60) is clamped to 59.
     */
    fun time(hour24: Int, minute: Int, second: Int, is24Hour: Boolean): String {
        val h = hour24.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val s = second.coerceIn(0, 59)
        val mmss = "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        if (is24Hour) return "${h.toString().padStart(2, '0')}:$mmss"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:$mmss ${if (h < 12) "AM" else "PM"}"
    }

    /**
     * "WEDNESDAY · 2026-10-21". [dayOfWeek] follows java.util.Calendar: Sunday = 1 .. Saturday = 7;
     * [month1] is 1..12.
     */
    fun date(dayOfWeek: Int, year: Int, month1: Int, day: Int): String {
        val weekday = WEEKDAYS[(dayOfWeek - 1).coerceIn(0, 6)]
        val y = year.toString().padStart(4, '0')
        val mo = month1.toString().padStart(2, '0')
        val d = day.toString().padStart(2, '0')
        return "${weekday.uppercase()} · $y-$mo-$d"
    }

    /** Index of the whole second [wallMillis] falls in (floor division, so it is right before 1970 too). */
    fun secondKey(wallMillis: Long): Long = Math.floorDiv(wallMillis, 1_000L)

    /** Index of the whole minute [wallMillis] falls in. */
    fun minuteKey(wallMillis: Long): Long = Math.floorDiv(wallMillis, 60_000L)

    /**
     * Milliseconds from [wallMillis] to just after the next whole second ([TICK_SLACK_MILLIS] past it). Always
     * more than [TICK_SLACK_MILLIS] and at most 1000 + [TICK_SLACK_MILLIS], so a repeating timer re-aimed from
     * the wall clock each time neither drifts nor spins.
     */
    fun millisToNextSecond(wallMillis: Long): Long =
        1_000L - Math.floorMod(wallMillis, 1_000L) + TICK_SLACK_MILLIS

    /** Milliseconds from [wallMillis] to just after the next whole minute. */
    fun millisToNextMinute(wallMillis: Long): Long =
        60_000L - Math.floorMod(wallMillis, 60_000L) + TICK_SLACK_MILLIS

    /**
     * True when a minute tick (ACTION_TIME_TICK) arrived in the last [EARLY_TICK_WINDOW_MILLIS] before the minute
     * boundary by the wall clock. The system aims the tick at the boundary, so this means it came a hair early:
     * drawing now would show the old minute, so the caller re-checks at [millisToNextMinute].
     */
    fun tickLandedEarly(wallMillis: Long): Boolean =
        Math.floorMod(wallMillis, 60_000L) >= 60_000L - EARLY_TICK_WINDOW_MILLIS
}

package com.andrewevmiller.tvlauncher.ambient

/** Pure text for the screensaver clock (the Android layer supplies the calendar fields). */
object OxyfieldClockText {
    private val WEEKDAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /** "19:42" in 24-hour mode, "7:42 PM" in 12-hour mode. */
    fun time(hour24: Int, minute: Int, is24Hour: Boolean): String {
        val h = hour24.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val mm = m.toString().padStart(2, '0')
        if (is24Hour) return "${h.toString().padStart(2, '0')}:$mm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:$mm ${if (h < 12) "AM" else "PM"}"
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
}

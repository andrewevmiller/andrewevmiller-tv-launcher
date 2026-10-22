package com.andrewevmiller.tvlauncher.viewinglog

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One block-character bar: [filled] full blocks, at most one [partial] eighth, then [rest] light blocks. */
data class TextBar(val filled: String, val partial: String, val rest: String) {
    /** The whole bar as one string, for measuring or tests. */
    val text: String get() = filled + partial + rest
}

/** One row of the bar chart: a label and the days it covers (one day for weeks, a Monday-to-Sunday run for months). */
data class LogBucket(val label: String, val days: DayRange)

/**
 * The words, numbers and layout of the Viewing log screen, as plain strings and lists, so the rules are unit tested
 * and the Compose layer only places them. Formats follow the mockup: `14 h 20 m`, `45 m`, `10-21`, `19:05–20:40`.
 */
object ViewingLogText {
    /** The line the empty state shows, so a user can grant usage access once. */
    const val GRANT_COMMAND = "adb shell appops set com.andrewevmiller.tvlauncher GET_USAGE_STATS allow"

    /** Cells in one bar. The longest bar fills all of them. */
    const val BAR_CELLS = 40

    private val PARTIALS = charArrayOf('▏', '▎', '▍', '▌', '▋', '▊', '▉')
    private const val FULL = '█'
    private const val LIGHT = '░'
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** `14 h 20 m` from an hour, `45 m` under one. Whole minutes; seconds are dropped. */
    fun duration(millis: Long): String {
        val minutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
        return if (minutes < 60) "$minutes m" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} m"
    }

    fun sessionCount(count: Int): String = if (count == 1) "1 session" else "$count sessions"

    /** `14 h 20 m · 11 sessions`, the summary under the screen's title. */
    fun summary(totalMillis: Long, count: Int): String = "${duration(totalMillis)} · ${sessionCount(count)}"

    /** The small caps word before the summary: `THIS WEEK`, `LAST WEEK` or `MONTH`. */
    fun heading(range: LogRange): String = when (range) {
        LogRange.THIS_WEEK -> "THIS WEEK"
        LogRange.LAST_WEEK -> "LAST WEEK"
        LogRange.MONTH -> "MONTH"
    }

    fun chipLabel(range: LogRange): String = when (range) {
        LogRange.THIS_WEEK -> "This week"
        LogRange.LAST_WEEK -> "Last week"
        LogRange.MONTH -> "Month"
    }

    /** The section label over the bars: per day for a week, per week for a month. */
    fun barsLabel(range: LogRange): String = if (range == LogRange.MONTH) "Per week · starting" else "Per day"

    /** Mon..Sun. Fixed English names: the mockup's and the TV's labels are in English. */
    fun weekday(day: LocalDate): String = WEEKDAYS[day.dayOfWeek.value - 1]

    /** `10-21`, the date above a day's sessions. */
    fun dateLabel(day: LocalDate): String = DATE.format(day)

    /** `19:05–20:40` in the zone the log is read in (en dash, as in the mockup). */
    fun timeRange(session: ViewingSession, zone: ZoneId): String {
        val from = TIME.withZone(zone).format(Instant.ofEpochMilli(session.startMillis))
        val to = TIME.withZone(zone).format(Instant.ofEpochMilli(session.endMillis))
        return "$from–$to"
    }

    /**
     * The bars for [range] as `LogBucket`s: seven days for a week; for a month, Monday-to-Sunday weeks clipped to the
     * month (the first one starts on the 1st, as in the mockup).
     */
    fun buckets(range: LogRange, today: LocalDate): List<LogBucket> {
        val days = ViewingStats.range(range, today)
        if (range != LogRange.MONTH) {
            return generateSequence(days.first) { it.plusDays(1) }
                .takeWhile { !it.isAfter(days.last) }
                .map { LogBucket(weekday(it), DayRange(it, it)) }
                .toList()
        }
        val out = ArrayList<LogBucket>()
        var start = days.first
        while (!start.isAfter(days.last)) {
            val end = minOf(ViewingStats.week(start).last, days.last)
            out.add(LogBucket(dateLabel(start), DayRange(start, end)))
            start = end.plusDays(1)
        }
        return out
    }

    /**
     * A bar of [cells] cells, scaled so [max] fills it. The fraction of a cell beyond the full blocks is drawn with
     * one eighth block (`▏`..`▉`), rounded to the nearest eighth. Zero or a negative max draws an empty bar.
     */
    fun textBar(value: Long, max: Long, cells: Int = BAR_CELLS): TextBar {
        val scaled = if (max > 0L && value > 0L) (value.toDouble() / max) * cells else 0.0
        val clamped = scaled.coerceIn(0.0, cells.toDouble())
        var full = clamped.toInt()
        val eighths = Math.round((clamped - full) * 8).toInt()
        if (eighths == 8) full += 1
        val partial = if (eighths in 1..7) PARTIALS[eighths - 1].toString() else ""
        val used = full + if (partial.isEmpty()) 0 else 1
        return TextBar(
            filled = FULL.toString().repeat(full.coerceAtMost(cells)),
            partial = partial,
            rest = LIGHT.toString().repeat((cells - used).coerceAtLeast(0)),
        )
    }

    /** Sessions grouped by the local date they start on, newest day first, newest session first within a day. */
    fun groupByDay(sessions: List<ViewingSession>, zone: ZoneId): List<Pair<LocalDate, List<ViewingSession>>> =
        sessions.sortedByDescending { it.startMillis }
            .groupBy { ViewingStats.startDay(it, zone) }
            .toList()
            .sortedByDescending { it.first }
}

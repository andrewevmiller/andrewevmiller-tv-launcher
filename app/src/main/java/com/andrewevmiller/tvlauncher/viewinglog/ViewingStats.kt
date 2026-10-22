package com.andrewevmiller.tvlauncher.viewinglog

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Whole local days, both ends inclusive. */
data class DayRange(val first: LocalDate, val last: LocalDate) {
    fun contains(day: LocalDate): Boolean = !day.isBefore(first) && !day.isAfter(last)
}

/** The three chips on the Viewing log screen. */
enum class LogRange { THIS_WEEK, LAST_WEEK, MONTH }

data class DayTotal(val day: LocalDate, val millis: Long)
data class AppTotal(val packageName: String, val millis: Long)
data class CategoryShare(val category: AppCategory, val millis: Long, val percent: Int)

/**
 * Totals over a range of days. Weeks run Monday to Sunday. A session that crosses midnight counts towards each
 * day it is in, so the per-day figures add up to the total. Pure: the zone is passed in.
 */
object ViewingStats {
    const val TOP_APPS = 5

    fun range(kind: LogRange, today: LocalDate): DayRange = when (kind) {
        LogRange.THIS_WEEK -> week(today)
        LogRange.LAST_WEEK -> week(today.minusWeeks(1))
        LogRange.MONTH -> DayRange(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
    }

    /** The Monday-to-Sunday week that holds [anyDay]. */
    fun week(anyDay: LocalDate): DayRange {
        val monday = anyDay.with(DayOfWeek.MONDAY)
        return DayRange(monday, monday.plusDays(6))
    }

    /** Total time in front during [range]. */
    fun total(sessions: List<ViewingSession>, range: DayRange, zone: ZoneId): Long =
        sessions.sumOf { overlapMillis(it, range, zone) }

    /** Total for the week that holds [anyDay]. */
    fun weekTotal(sessions: List<ViewingSession>, anyDay: LocalDate, zone: ZoneId): Long =
        total(sessions, week(anyDay), zone)

    /** Total for the calendar month that holds [anyDay]. */
    fun monthTotal(sessions: List<ViewingSession>, anyDay: LocalDate, zone: ZoneId): Long =
        total(sessions, DayRange(anyDay.withDayOfMonth(1), anyDay.withDayOfMonth(anyDay.lengthOfMonth())), zone)

    /** One entry per day in [range], in order, zero days included. */
    fun perDay(sessions: List<ViewingSession>, range: DayRange, zone: ZoneId): List<DayTotal> =
        generateSequence(range.first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(range.last) }
            .map { day -> DayTotal(day, total(sessions, DayRange(day, day), zone)) }
            .toList()

    /** The apps with the most time in [range], most first; ties by package name. */
    fun topApps(
        sessions: List<ViewingSession>,
        range: DayRange,
        zone: ZoneId,
        limit: Int = TOP_APPS,
    ): List<AppTotal> =
        sessions.groupBy { it.packageName }
            .map { (pkg, list) -> AppTotal(pkg, total(list, range, zone)) }
            .filter { it.millis > 0 }
            .sortedWith(compareByDescending<AppTotal> { it.millis }.thenBy { it.packageName })
            .take(limit)

    /** Each category's share of the time in [range]. [categoryOf] comes from the app list; percents are rounded. */
    fun categoryShares(
        sessions: List<ViewingSession>,
        range: DayRange,
        zone: ZoneId,
        categoryOf: (String) -> AppCategory,
    ): List<CategoryShare> {
        val byCategory = sessions.groupBy { categoryOf(it.packageName) }
            .mapValues { (_, list) -> total(list, range, zone) }
            .filterValues { it > 0 }
        val all = byCategory.values.sum()
        if (all <= 0L) return emptyList()
        return byCategory.map { (category, millis) ->
            CategoryShare(category, millis, (100.0 * millis / all).roundToInt())
        }.sortedWith(compareByDescending<CategoryShare> { it.millis }.thenBy { it.category.name })
    }

    /** Sessions that overlap [range], newest first: the list under the summary, grouped by start date. */
    fun sessionsIn(sessions: List<ViewingSession>, range: DayRange, zone: ZoneId): List<ViewingSession> =
        sessions.filter { overlapMillis(it, range, zone) > 0 }.sortedByDescending { it.startMillis }

    /** The local date a session starts on, for grouping. */
    fun startDay(session: ViewingSession, zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(session.startMillis).atZone(zone).toLocalDate()

    /** Milliseconds of [session] inside the days of [range]. */
    fun overlapMillis(session: ViewingSession, range: DayRange, zone: ZoneId): Long {
        val from = range.first.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = range.last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return maxOf(0L, minOf(session.endMillis, to) - maxOf(session.startMillis, from))
    }
}

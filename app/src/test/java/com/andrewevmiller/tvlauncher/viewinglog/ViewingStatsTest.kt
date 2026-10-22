package com.andrewevmiller.tvlauncher.viewinglog

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewingStatsTest {
    private val zone = ZoneOffset.UTC
    private fun millis(day: String, hm: String): Long =
        LocalDate.parse(day).atTime(java.time.LocalTime.parse(hm)).atZone(zone).toInstant().toEpochMilli()
    private fun session(pkg: String, day: String, from: String, to: String) =
        ViewingSession(pkg, millis(day, from), millis(day, to))

    private val youtube = "com.google.android.youtube.tv"
    private val plex = "com.plexapp.android"
    private val spotify = "com.spotify.tv.android"
    private val netflix = "com.netflix.ninja"

    /** Today is Thursday 2026-10-22. This week is 10-19..10-25; last week is 10-12..10-18. */
    private val today = LocalDate.parse("2026-10-22")
    private val sessions = listOf(
        session(youtube, "2026-10-19", "19:00", "20:30"),   // 90 min, Monday
        ViewingSession(spotify, millis("2026-10-20", "23:30"), millis("2026-10-21", "00:30")), // 30 min Tue, 30 min Wed
        session(plex, "2026-10-22", "10:00", "10:45"),      // 45 min, today
        session(netflix, "2026-10-12", "20:00", "22:00"),   // 120 min, last week
    )
    private val categories = mapOf(
        youtube to AppCategory.VIDEO,
        plex to AppCategory.VIDEO,
        spotify to AppCategory.MUSIC,
        netflix to AppCategory.VIDEO,
    )
    private fun categoryOf(pkg: String) = categories[pkg] ?: AppCategory.OTHER

    @Test
    fun thisWeekIsMondayToSunday() {
        val range = ViewingStats.range(LogRange.THIS_WEEK, today)
        assertEquals(LocalDate.parse("2026-10-19"), range.first)
        assertEquals(LocalDate.parse("2026-10-25"), range.last)
    }

    @Test
    fun lastWeekIsThePreviousMondayToSunday() {
        val range = ViewingStats.range(LogRange.LAST_WEEK, today)
        assertEquals(LocalDate.parse("2026-10-12"), range.first)
        assertEquals(LocalDate.parse("2026-10-18"), range.last)
    }

    @Test
    fun monthIsTheWholeCalendarMonth() {
        val range = ViewingStats.range(LogRange.MONTH, today)
        assertEquals(LocalDate.parse("2026-10-01"), range.first)
        assertEquals(LocalDate.parse("2026-10-31"), range.last)
        val feb = ViewingStats.range(LogRange.MONTH, LocalDate.parse("2028-02-10"))
        assertEquals(LocalDate.parse("2028-02-29"), feb.last)
    }

    @Test
    fun weekOfASundayStartsOnThatWeeksMonday() {
        val week = ViewingStats.week(LocalDate.parse("2026-10-25"))
        assertEquals(LocalDate.parse("2026-10-19"), week.first)
    }

    @Test
    fun weekTotalAddsTheSessionsInTheWeek() {
        // 90 + 30 + 30 + 45 minutes
        assertEquals(195 * 60_000L, ViewingStats.weekTotal(sessions, today, zone))
        assertEquals(120 * 60_000L, ViewingStats.weekTotal(sessions, today.minusWeeks(1), zone))
    }

    @Test
    fun monthTotalAddsTheSessionsInTheMonth() {
        assertEquals((195 + 120) * 60_000L, ViewingStats.monthTotal(sessions, today, zone))
    }

    @Test
    fun aSessionCrossingMidnightCountsOnEachDay() {
        val perDay = ViewingStats.perDay(sessions, ViewingStats.range(LogRange.THIS_WEEK, today), zone)
        assertEquals(7, perDay.size)
        assertEquals(listOf(90, 30, 30, 45, 0, 0, 0), perDay.map { (it.millis / 60_000).toInt() })
        assertEquals(LocalDate.parse("2026-10-19"), perDay.first().day)
        assertEquals(LocalDate.parse("2026-10-25"), perDay.last().day)
    }

    @Test
    fun perDayTotalsAddUpToTheRangeTotal() {
        val range = ViewingStats.range(LogRange.THIS_WEEK, today)
        assertEquals(ViewingStats.total(sessions, range, zone), ViewingStats.perDay(sessions, range, zone).sumOf { it.millis })
    }

    @Test
    fun topAppsAreSortedByDurationWithAName() {
        val range = ViewingStats.range(LogRange.MONTH, today)
        val top = ViewingStats.topApps(sessions, range, zone)
        assertEquals(listOf(netflix, youtube, spotify, plex), top.map { it.packageName })
        assertEquals(120 * 60_000L, top.first().millis)
    }

    @Test
    fun topAppsAreLimited() {
        val many = (1..8).map { ViewingSession("app$it", millis("2026-10-19", "10:00"), millis("2026-10-19", "10:0$it")) }
        assertEquals(ViewingStats.TOP_APPS, ViewingStats.topApps(many, ViewingStats.range(LogRange.THIS_WEEK, today), zone).size)
    }

    @Test
    fun categorySharesAreOfTheRangeAndRounded() {
        // This week: VIDEO is YouTube 90 + Plex 45 = 135 of 195 (69%); MUSIC is Spotify 60 (31%).
        val shares = ViewingStats.categoryShares(sessions, ViewingStats.range(LogRange.THIS_WEEK, today), zone, ::categoryOf)
        assertEquals(listOf(AppCategory.VIDEO, AppCategory.MUSIC), shares.map { it.category })
        assertEquals(listOf(69, 31), shares.map { it.percent })
        assertEquals(135 * 60_000L, shares[0].millis)
    }

    @Test
    fun noTimeMeansNoShares() {
        assertTrue(ViewingStats.categoryShares(emptyList(), ViewingStats.range(LogRange.THIS_WEEK, today), zone, ::categoryOf).isEmpty())
    }

    @Test
    fun sessionsInARangeAreNewestFirst() {
        val list = ViewingStats.sessionsIn(sessions, ViewingStats.range(LogRange.THIS_WEEK, today), zone)
        assertEquals(listOf(plex, spotify, youtube), list.map { it.packageName })
        assertTrue(list.zipWithNext().all { (a, b) -> a.startMillis >= b.startMillis })
    }

    @Test
    fun startDayIsTheLocalDateOfTheStart() {
        assertEquals(LocalDate.parse("2026-10-20"), ViewingStats.startDay(sessions[1], zone))
    }

    @Test
    fun emptyLogGivesZeroes() {
        val range = ViewingStats.range(LogRange.THIS_WEEK, today)
        assertEquals(0L, ViewingStats.total(emptyList(), range, zone))
        assertTrue(ViewingStats.topApps(emptyList(), range, zone).isEmpty())
        assertEquals(7, ViewingStats.perDay(emptyList(), range, zone).size)
    }

    @Test
    fun overlapIsClippedToTheRange() {
        val s = ViewingSession(plex, millis("2026-10-18", "23:00"), millis("2026-10-19", "01:00"))
        assertEquals(60 * 60_000L, ViewingStats.overlapMillis(s, ViewingStats.range(LogRange.THIS_WEEK, today), zone))
    }
}

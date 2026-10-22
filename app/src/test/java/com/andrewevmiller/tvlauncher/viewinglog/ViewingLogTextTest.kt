package com.andrewevmiller.tvlauncher.viewinglog

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewingLogTextTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-22")
    private val minute = 60_000L

    @Test
    fun durationsFollowTheMockup() {
        assertEquals("0 m", ViewingLogText.duration(0))
        assertEquals("45 m", ViewingLogText.duration(45 * minute))
        assertEquals("59 m", ViewingLogText.duration(59 * minute + 59_000))
        assertEquals("1 h 00 m", ViewingLogText.duration(60 * minute))
        assertEquals("14 h 20 m", ViewingLogText.duration((14 * 60 + 20) * minute))
        assertEquals("0 m", ViewingLogText.duration(-5 * minute))
    }

    @Test
    fun summaryReadsLikeTheMockup() {
        assertEquals("14 h 20 m · 11 sessions", ViewingLogText.summary((14 * 60 + 20) * minute, 11))
        assertEquals("1 session", ViewingLogText.sessionCount(1))
        assertEquals("0 sessions", ViewingLogText.sessionCount(0))
        assertEquals("0 m · 0 sessions", ViewingLogText.summary(0, 0))
    }

    @Test
    fun headingsAndChipsForEachRange() {
        assertEquals("THIS WEEK", ViewingLogText.heading(LogRange.THIS_WEEK))
        assertEquals("LAST WEEK", ViewingLogText.heading(LogRange.LAST_WEEK))
        assertEquals("MONTH", ViewingLogText.heading(LogRange.MONTH))
        assertEquals("This week", ViewingLogText.chipLabel(LogRange.THIS_WEEK))
        assertEquals("Last week", ViewingLogText.chipLabel(LogRange.LAST_WEEK))
        assertEquals("Month", ViewingLogText.chipLabel(LogRange.MONTH))
        assertEquals("Per day", ViewingLogText.barsLabel(LogRange.THIS_WEEK))
        assertEquals("Per week · starting", ViewingLogText.barsLabel(LogRange.MONTH))
    }

    @Test
    fun weekdaysAndDatesAreMondayFirst() {
        assertEquals("Mon", ViewingLogText.weekday(LocalDate.parse("2026-10-19")))
        assertEquals("Thu", ViewingLogText.weekday(LocalDate.parse("2026-10-22")))
        assertEquals("Sun", ViewingLogText.weekday(LocalDate.parse("2026-10-25")))
        assertEquals("10-21", ViewingLogText.dateLabel(LocalDate.parse("2026-10-21")))
    }

    @Test
    fun timeRangeUsesTwentyFourHourClockAndAnEnDash() {
        val at = LocalDate.parse("2026-10-21").atTime(19, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        val session = ViewingSession("com.x", at, at + 95 * minute)
        assertEquals("19:05–20:40", ViewingLogText.timeRange(session, zone))
    }

    @Test
    fun aWeekHasSevenDayBuckets() {
        val buckets = ViewingLogText.buckets(LogRange.THIS_WEEK, today)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), buckets.map { it.label })
        assertEquals(LocalDate.parse("2026-10-19"), buckets.first().days.first)
        assertEquals(buckets.first().days.first, buckets.first().days.last)
    }

    @Test
    fun aMonthIsWeeklyBucketsThatCoverItExactly() {
        val buckets = ViewingLogText.buckets(LogRange.MONTH, today)
        // October 2026 starts on a Thursday: the first bucket is 10-01 to 10-04, then whole Monday-to-Sunday weeks.
        assertEquals(listOf("10-01", "10-05", "10-12", "10-19", "10-26"), buckets.map { it.label })
        assertEquals(LocalDate.parse("2026-10-04"), buckets.first().days.last)
        assertEquals(LocalDate.parse("2026-10-31"), buckets.last().days.last)
        for (pair in buckets.zipWithNext()) {
            assertEquals(pair.first.days.last.plusDays(1), pair.second.days.first)
        }
    }

    @Test
    fun aFullBarFillsEveryCell() {
        val bar = ViewingLogText.textBar(100, 100)
        assertEquals("█".repeat(40), bar.text)
        assertEquals("", bar.partial)
    }

    @Test
    fun anEmptyBarIsAllLightBlocks() {
        assertEquals("░".repeat(40), ViewingLogText.textBar(0, 100).text)
        assertEquals("░".repeat(40), ViewingLogText.textBar(5, 0).text)
    }

    @Test
    fun aHalfBarIsTwentyCells() {
        val bar = ViewingLogText.textBar(50, 100)
        assertEquals("█".repeat(20), bar.filled)
        assertEquals("░".repeat(20), bar.rest)
        assertEquals("", bar.partial)
    }

    @Test
    fun aPartialCellUsesTheNearestEighth() {
        // 20.5 cells: 20 full blocks, then a half block (4/8).
        val bar = ViewingLogText.textBar(205, 400)
        assertEquals("█".repeat(20), bar.filled)
        assertEquals("▌", bar.partial)
        assertEquals("░".repeat(19), bar.rest)
        assertEquals(40, bar.text.length)
    }

    @Test
    fun aNearlyFullCellRoundsUpToTheNextFullBlock() {
        // 20.97 cells is 7.76 eighths, which rounds to 8: a full 21st block, no partial.
        val bar = ViewingLogText.textBar(2097, 4000)
        assertEquals("█".repeat(21), bar.filled)
        assertEquals("", bar.partial)
        assertEquals("░".repeat(19), bar.rest)
    }

    @Test
    fun everyBarIsExactlyTheCellCount() {
        for (value in 0L..300L step 7L) {
            assertEquals("value=$value", 40, ViewingLogText.textBar(value, 300).text.length)
        }
    }

    @Test
    fun groupingPutsNewestDayAndNewestSessionFirst() {
        fun at(day: String, hm: String) = LocalDate.parse(day).atTime(java.time.LocalTime.parse(hm)).toInstant(ZoneOffset.UTC).toEpochMilli()
        val a = ViewingSession("com.a", at("2026-10-20", "09:00"), at("2026-10-20", "09:30"))
        val b = ViewingSession("com.b", at("2026-10-21", "08:00"), at("2026-10-21", "08:30"))
        val c = ViewingSession("com.c", at("2026-10-21", "18:00"), at("2026-10-21", "18:30"))
        val groups = ViewingLogText.groupByDay(listOf(a, b, c), zone)
        assertEquals(listOf(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-20")), groups.map { it.first })
        assertEquals(listOf(c, b), groups[0].second)
        assertEquals(listOf(a), groups[1].second)
        assertTrue(ViewingLogText.groupByDay(emptyList(), zone).isEmpty())
    }

    @Test
    fun theGrantCommandIsTheOneInTheDocs() {
        assertEquals(
            "adb shell appops set com.andrewevmiller.tvlauncher GET_USAGE_STATS allow",
            ViewingLogText.GRANT_COMMAND,
        )
    }
}

package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTextTest {
    @Test
    fun twentyFourHourTimeIsZeroPadded() {
        assertEquals("19:42", OxyfieldClockText.time(19, 42, true))
        assertEquals("00:05", OxyfieldClockText.time(0, 5, true))
        assertEquals("09:00", OxyfieldClockText.time(9, 0, true))
        assertEquals("23:59", OxyfieldClockText.time(23, 59, true))
    }

    @Test
    fun twelveHourTimeUsesAmPmWithoutALeadingZero() {
        assertEquals("7:42 PM", OxyfieldClockText.time(19, 42, false))
        assertEquals("12:00 AM", OxyfieldClockText.time(0, 0, false))
        assertEquals("12:30 PM", OxyfieldClockText.time(12, 30, false))
        assertEquals("1:05 AM", OxyfieldClockText.time(1, 5, false))
        assertEquals("11:59 PM", OxyfieldClockText.time(23, 59, false))
    }

    @Test
    fun outOfRangeFieldsAreClampedNotThrown() {
        assertEquals("23:59", OxyfieldClockText.time(99, 99, true))
        assertEquals("00:00", OxyfieldClockText.time(-4, -1, true))
    }

    @Test
    fun twentyFourHourTimeWithSecondsIsZeroPadded() {
        assertEquals("19:42:07", OxyfieldClockText.time(19, 42, 7, true))
        assertEquals("00:00:00", OxyfieldClockText.time(0, 0, 0, true))
        assertEquals("09:05:03", OxyfieldClockText.time(9, 5, 3, true))
        assertEquals("12:00:00", OxyfieldClockText.time(12, 0, 0, true))
        assertEquals("23:59:59", OxyfieldClockText.time(23, 59, 59, true))
    }

    @Test
    fun twelveHourTimeWithSecondsUsesAmPmAndNoLeadingZeroOnTheHour() {
        assertEquals("7:42:07 PM", OxyfieldClockText.time(19, 42, 7, false))
        // Midnight is 12 AM and noon is 12 PM.
        assertEquals("12:00:00 AM", OxyfieldClockText.time(0, 0, 0, false))
        assertEquals("12:00:00 PM", OxyfieldClockText.time(12, 0, 0, false))
        assertEquals("12:30:15 PM", OxyfieldClockText.time(12, 30, 15, false))
        assertEquals("1:05:09 AM", OxyfieldClockText.time(1, 5, 9, false))
        assertEquals("11:59:59 AM", OxyfieldClockText.time(11, 59, 59, false))
        assertEquals("11:59:59 PM", OxyfieldClockText.time(23, 59, 59, false))
        // The widest string the clock can show.
        assertEquals("12:59:59 PM", OxyfieldClockText.time(12, 59, 59, false))
    }

    @Test
    fun secondsAreAlwaysTwoDigitsAndTheLongestStringIsElevenCharacters() {
        for (second in 0..59) {
            val text = OxyfieldClockText.time(8, 3, second, true)
            assertEquals("seconds field of $text", 2, text.substringAfterLast(':').length)
        }
        var longest = 0
        for (hour in 0..23) {
            for (is24 in listOf(true, false)) {
                longest = maxOf(longest, OxyfieldClockText.time(hour, 59, 59, is24).length)
            }
        }
        assertEquals(11, longest) // "10:59:59 PM", "12:59:59 AM": the layout is checked against this
    }

    @Test
    fun outOfRangeFieldsWithSecondsAreClampedNotThrown() {
        assertEquals("23:59:59", OxyfieldClockText.time(99, 99, 99, true))
        assertEquals("00:00:00", OxyfieldClockText.time(-4, -1, -30, true))
        // A leap second (60) shows as 59.
        assertEquals("23:59:59", OxyfieldClockText.time(23, 59, 60, true))
        assertEquals("11:59:59 PM", OxyfieldClockText.time(23, 59, 60, false))
        assertEquals("12:00:00 AM", OxyfieldClockText.time(-1, -1, -1, false))
    }

    @Test
    fun theMinuteOnlyTextIsTheSecondsTextWithoutTheSeconds() {
        // The sleep screen uses the minute-only overload, so it must agree with the seconds one up to the seconds.
        for (hour in 0..23) {
            for (is24 in listOf(true, false)) {
                val minuteOnly = OxyfieldClockText.time(hour, 42, is24)
                val withSeconds = OxyfieldClockText.time(hour, 42, 7, is24)
                assertEquals(minuteOnly, withSeconds.replace(":07", ""))
            }
        }
    }

    @Test
    fun secondAndMinuteKeysChangeExactlyOnTheBoundaries() {
        assertEquals(0L, OxyfieldClockText.secondKey(0L))
        assertEquals(0L, OxyfieldClockText.secondKey(999L))
        assertEquals(1L, OxyfieldClockText.secondKey(1_000L))
        assertEquals(-1L, OxyfieldClockText.secondKey(-1L)) // before 1970 still floors
        assertEquals(0L, OxyfieldClockText.minuteKey(59_999L))
        assertEquals(1L, OxyfieldClockText.minuteKey(60_000L))
        assertEquals(-1L, OxyfieldClockText.minuteKey(-1L))
    }

    @Test
    fun timersAreAimedJustAfterTheNextBoundaryNeverBeforeIt() {
        val slack = OxyfieldClockText.TICK_SLACK_MILLIS
        assertEquals(1_000L + slack, OxyfieldClockText.millisToNextSecond(0L))
        assertEquals(1L + slack, OxyfieldClockText.millisToNextSecond(999L))
        assertEquals(750L + slack, OxyfieldClockText.millisToNextSecond(1_250L))
        assertEquals(1L + slack, OxyfieldClockText.millisToNextSecond(-1L))
        for (wall in listOf(0L, 1L, 499L, 500L, 999L, 1_000L, 123_456_789L, -250L)) {
            val delay = OxyfieldClockText.millisToNextSecond(wall)
            assertTrue("delay $delay for $wall", delay > slack && delay <= 1_000L + slack)
            // Landing there is always in the next whole second.
            assertEquals(OxyfieldClockText.secondKey(wall) + 1, OxyfieldClockText.secondKey(wall + delay))
        }
        assertEquals(60_000L + slack, OxyfieldClockText.millisToNextMinute(0L))
        assertEquals(1L + slack, OxyfieldClockText.millisToNextMinute(59_999L))
        assertEquals(OxyfieldClockText.minuteKey(100_000L) + 1, OxyfieldClockText.minuteKey(100_000L + OxyfieldClockText.millisToNextMinute(100_000L)))
    }

    @Test
    fun aMinuteTickInTheLastSecondBeforeTheBoundaryCountsAsEarly() {
        assertFalse(OxyfieldClockText.tickLandedEarly(0L)) // on time
        assertFalse(OxyfieldClockText.tickLandedEarly(150L))
        assertFalse(OxyfieldClockText.tickLandedEarly(30_000L)) // late is not early
        assertFalse(OxyfieldClockText.tickLandedEarly(58_999L))
        assertTrue(OxyfieldClockText.tickLandedEarly(59_000L))
        assertTrue(OxyfieldClockText.tickLandedEarly(59_999L))
        assertTrue(OxyfieldClockText.tickLandedEarly(120_000L - 5L))
        assertFalse(OxyfieldClockText.tickLandedEarly(120_000L))
    }

    @Test
    fun dateLineMatchesTheMockup() {
        // 2026-10-21 is a Wednesday; java.util.Calendar numbers Sunday as 1.
        assertEquals("WEDNESDAY · 2026-10-21", OxyfieldClockText.date(4, 2026, 10, 21))
        assertEquals("SUNDAY · 2026-01-04", OxyfieldClockText.date(1, 2026, 1, 4))
        assertEquals("SATURDAY · 2026-12-31", OxyfieldClockText.date(7, 2026, 12, 31))
    }

    @Test
    fun badWeekdayDoesNotThrow() {
        assertEquals("SUNDAY · 2026-01-01", OxyfieldClockText.date(0, 2026, 1, 1))
        assertEquals("SATURDAY · 2026-01-01", OxyfieldClockText.date(42, 2026, 1, 1))
    }
}

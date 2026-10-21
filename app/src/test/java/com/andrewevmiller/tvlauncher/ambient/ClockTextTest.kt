package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertEquals
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

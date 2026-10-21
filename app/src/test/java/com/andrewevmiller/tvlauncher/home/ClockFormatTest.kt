package com.andrewevmiller.tvlauncher.home

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFormatTest {
    private fun at(zone: String, hour: Int = 19, minute: Int = 42, second: Int = 7) =
        ZonedDateTime.of(2026, 10, 21, hour, minute, second, 0, ZoneId.of(zone))

    @Test
    fun twentyFourHourShowsSecondsAndZone() {
        assertEquals("2026-10-21 · 19:42:07 PDT", ClockFormat.format(at("America/Los_Angeles"), true, Locale.US))
    }

    @Test
    fun twelveHourShowsSecondsMeridiemAndZone() {
        assertEquals("2026-10-21 · 7:42:07 PM PDT", ClockFormat.format(at("America/Los_Angeles"), false, Locale.US))
        assertEquals("2026-10-21 · 12:00:00 AM PDT", ClockFormat.format(at("America/Los_Angeles", 0, 0, 0), false, Locale.US))
    }

    @Test
    fun zoneFollowsTheZoneOfTheInstant() {
        assertEquals("2026-10-21 · 19:42:07 UTC", ClockFormat.format(at("UTC"), true, Locale.US))
        assertEquals("2026-10-21 · 19:42:07 IST", ClockFormat.format(at("Asia/Kolkata"), true, Locale.US))
        // the same wall time in another zone reads differently only in the zone suffix
        assertEquals("2026-10-21 · 19:42:07 EDT", ClockFormat.format(at("America/New_York"), true, Locale.US))
    }

    @Test
    fun secondsAreZeroPadded() {
        assertEquals("2026-10-21 · 01:02:03 UTC", ClockFormat.format(at("UTC", 1, 2, 3), true, Locale.US))
    }

    @Test
    fun sleepsUntilTheNextWholeSecond() {
        assertEquals(1000L, ClockFormat.millisUntilNextSecond(0))
        assertEquals(500L, ClockFormat.millisUntilNextSecond(500_000_000))
        assertEquals(1L, ClockFormat.millisUntilNextSecond(999_999_999))
        assertEquals(1L, ClockFormat.millisUntilNextSecond(999_000_000))
    }
}

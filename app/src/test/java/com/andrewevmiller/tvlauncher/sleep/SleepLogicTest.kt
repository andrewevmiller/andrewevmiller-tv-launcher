package com.andrewevmiller.tvlauncher.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SleepLogicTest {
    private val minute = 60_000L
    private val second = 1_000L
    private val cap = SleepPresets.MAX_MINUTES * minute

    // --- remaining ---

    @Test
    fun remainingCountsDownAndNeverGoesNegative() {
        val running = SleepTimerState.Running(deadlineAtMillis = 10 * minute, totalMillis = 10 * minute)
        assertEquals(10 * minute, SleepLogic.remaining(running, 0L))
        assertEquals(4 * minute, SleepLogic.remaining(running, 6 * minute))
        assertEquals(0L, SleepLogic.remaining(running, 10 * minute))
        assertEquals(0L, SleepLogic.remaining(running, 11 * minute))
    }

    // --- caption ---

    @Test
    fun captionRoundsUpToWholeMinutes() {
        assertEquals("42 min", SleepLogic.caption(42 * minute))
        assertEquals("42 min", SleepLogic.caption(41 * minute + 30 * second + 1))
        assertEquals("43 min", SleepLogic.caption(42 * minute + 1))
        assertEquals("1 min", SleepLogic.caption(minute))
    }

    @Test
    fun captionAt5959IsOneHourNotSixtyMinutes() {
        // 59 min 1 s rounds up to 60 whole minutes, and the hours format is chosen from the rounded value.
        assertEquals("1 h 00 min", SleepLogic.caption(59 * minute + second))
        assertEquals("1 h 00 min", SleepLogic.caption(59 * minute + 59 * second + 999))
        assertEquals("59 min", SleepLogic.caption(59 * minute))
    }

    @Test
    fun captionAtExactHoursAndAfter() {
        assertEquals("1 h 00 min", SleepLogic.caption(60 * minute))
        assertEquals("1 h 05 min", SleepLogic.caption(65 * minute))
        assertEquals("1 h 06 min", SleepLogic.caption(65 * minute + 30 * second))
        assertEquals("2 h 00 min", SleepLogic.caption(120 * minute))
        assertEquals("4 h 00 min", SleepLogic.caption(cap))
    }

    @Test
    fun captionBelowOneMinuteIsLessThanOneMinuteWhileTimeIsLeft() {
        assertEquals("<1 min", SleepLogic.caption(1L))
        assertEquals("<1 min", SleepLogic.caption(500L))
        assertEquals("<1 min", SleepLogic.caption(minute - 1))
    }

    @Test
    fun captionAroundTheOneMinuteMarkFollowsTheRoundUpRule() {
        // Literal rule: "<1 min" below one minute, then whole minutes rounded up. So "1 min" is only shown at exactly
        // one minute, and the caption reads "2 min" from just above one minute. See the report for the UX note.
        assertEquals("<1 min", SleepLogic.caption(minute - 1))
        assertEquals("1 min", SleepLogic.caption(minute))
        assertEquals("2 min", SleepLogic.caption(minute + 1))
    }

    @Test
    fun captionAtOrBelowZeroIsZeroMinutes() {
        assertEquals("0 min", SleepLogic.caption(0L))
        assertEquals("0 min", SleepLogic.caption(-1L))
        assertEquals("0 min", SleepLogic.caption(Long.MIN_VALUE))
    }

    @Test
    fun captionNeverReadsZeroWhileTimeIsLeft() {
        for (ms in listOf(1L, 999L, 500L, 59_999L, 60_000L, 60_001L, 3_599_999L, 3_600_001L)) {
            assertFalse("for $ms", SleepLogic.caption(ms) == "0 min")
        }
    }

    // --- clock ---

    @Test
    fun clockFormatsHoursMinutesAndSeconds() {
        assertEquals("0:42:10", SleepLogic.clock(42 * minute + 10 * second))
        assertEquals("2:00:00", SleepLogic.clock(120 * minute))
        assertEquals("1:00:00", SleepLogic.clock(60 * minute))
        assertEquals("3:59:59", SleepLogic.clock(cap - second))
        assertEquals("0:00:01", SleepLogic.clock(second))
    }

    @Test
    fun clockRoundsUpToTheNextWholeSecond() {
        assertEquals("0:00:01", SleepLogic.clock(500L))
        assertEquals("0:00:01", SleepLogic.clock(1L))
        assertEquals("0:00:02", SleepLogic.clock(second + 1))
        assertEquals("0:42:11", SleepLogic.clock(42 * minute + 10 * second + 1))
        assertEquals("1:00:00", SleepLogic.clock(59 * minute + 59 * second + 500))
    }

    @Test
    fun clockIsZeroAndNeverNegativeAtOrPastTheDeadline() {
        assertEquals("0:00:00", SleepLogic.clock(0L))
        assertEquals("0:00:00", SleepLogic.clock(-1L))
        assertEquals("0:00:00", SleepLogic.clock(Long.MIN_VALUE))
    }

    // --- start ---

    @Test
    fun startUsesThePresetLengthFromNow() {
        assertEquals(SleepTimerState.Running(1_000L + 30 * minute, 30 * minute), SleepLogic.start(1_000L, 30))
        for (m in SleepPresets.minutes) {
            val running = SleepLogic.start(0L, m)
            assertEquals("for $m", m * minute, running.totalMillis)
            assertEquals("for $m", m * minute, running.deadlineAtMillis)
        }
    }

    @Test
    fun startSanitisesANonPresetToTheDefault() {
        assertEquals(SleepPresets.DEFAULT_MINUTES * minute, SleepLogic.start(0L, 7).totalMillis)
        assertEquals(SleepPresets.DEFAULT_MINUTES * minute, SleepLogic.start(0L, SleepPresets.MAX_MINUTES).totalMillis)
    }

    // --- extend ---

    @Test
    fun extendIsANoOpUnlessRunning() {
        assertEquals(SleepTimerState.Idle, SleepLogic.extend(SleepTimerState.Idle, 0L, 15))
        assertEquals(SleepTimerState.Firing, SleepLogic.extend(SleepTimerState.Firing, 0L, 15))
    }

    @Test
    fun extendMovesTheDeadlineAndTheTotal() {
        val started = SleepLogic.start(0L, 120)
        assertEquals(
            SleepTimerState.Running(deadlineAtMillis = 135 * minute, totalMillis = 135 * minute),
            SleepLogic.extend(started, 0L, 15),
        )
    }

    @Test
    fun extendStopsAtTheCapFromNow() {
        var state: SleepTimerState = SleepLogic.start(0L, 120)
        repeat(20) { state = SleepLogic.extend(state, 0L, 15) }
        assertEquals(SleepTimerState.Running(cap, cap), state)
    }

    @Test
    fun theCapMovesWithNow() {
        var state: SleepTimerState = SleepLogic.start(0L, 120)
        repeat(20) { state = SleepLogic.extend(state, 0L, 15) }
        // An hour later the cap is 60 + 240 = 300 min, so 15 more minutes fit and the deadline is 255 min.
        state = SleepLogic.extend(state, 60 * minute, 15)
        assertEquals(SleepTimerState.Running(255 * minute, 255 * minute), state)
        // And a large extension is cut at the new cap, 300 min.
        state = SleepLogic.extend(state, 60 * minute, 120)
        assertEquals(SleepTimerState.Running(300 * minute, 300 * minute), state)
    }

    @Test
    fun aDeadlineAlreadyPastTheCapIsPulledBackToIt() {
        // Only reachable from a corrupt state or a clock moved backwards. Same arithmetic as the fake.
        val beyond = SleepTimerState.Running(deadlineAtMillis = 500 * minute, totalMillis = 500 * minute)
        assertEquals(SleepTimerState.Running(cap, cap), SleepLogic.extend(beyond, 0L, 15))
    }

    // --- restore ---

    @Test
    fun restoreNeedsBothValues() {
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(null, null, 0L))
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(null, 30 * minute, 0L))
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(30 * minute, null, 0L))
    }

    @Test
    fun restoreNeedsAPositiveTotal() {
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(30 * minute, 0L, 0L))
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(30 * minute, -minute, 0L))
    }

    @Test
    fun restoreDropsADeadlineThatPassedWhileTheTvWasOff() {
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(10 * minute, 10 * minute, 11 * minute))
        // Exactly at the deadline it has fired, so it is not restored either.
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(10 * minute, 10 * minute, 10 * minute))
    }

    @Test
    fun restoreDropsADeadlineFurtherThanTheCapAsCorrupt() {
        val now = 1_000L
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(now + cap + 1, 60 * minute, now))
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(Long.MAX_VALUE, 60 * minute, now))
        // Exactly the cap is still allowed.
        assertEquals(
            SleepTimerState.Running(now + cap, 60 * minute),
            SleepLogic.restore(now + cap, 60 * minute, now),
        )
    }

    @Test
    fun restoreTheCapMovesWithNow() {
        // Stored deadline 300 min: too far from 0, but within the cap an hour later.
        assertEquals(SleepTimerState.Idle, SleepLogic.restore(300 * minute, 300 * minute, 0L))
        assertEquals(
            SleepTimerState.Running(300 * minute, 300 * minute),
            SleepLogic.restore(300 * minute, 300 * minute, 60 * minute),
        )
    }

    @Test
    fun restoreKeepsAValidTimerAsItWas() {
        assertEquals(
            SleepTimerState.Running(deadlineAtMillis = 1_000L + 30 * minute, totalMillis = 45 * minute),
            SleepLogic.restore(1_000L + 30 * minute, 45 * minute, 1_000L),
        )
        val running = SleepLogic.start(5_000L, 90)
        assertEquals(running, SleepLogic.restore(running.deadlineAtMillis, running.totalMillis, 5_000L))
    }

    // --- isDue ---

    @Test
    fun isDueOnlyWhenRunningAndAtOrPastTheDeadline() {
        val running = SleepTimerState.Running(deadlineAtMillis = 100L, totalMillis = 100L)
        assertFalse(SleepLogic.isDue(running, 99L))
        assertTrue(SleepLogic.isDue(running, 100L))
        assertTrue(SleepLogic.isDue(running, 101L))
        assertFalse(SleepLogic.isDue(SleepTimerState.Idle, Long.MAX_VALUE))
        assertFalse(SleepLogic.isDue(SleepTimerState.Firing, Long.MAX_VALUE))
    }

    // --- parity with the fake ---

    /** Drives the fake and the pure logic with the same calls, keeping both states to compare. */
    private class Rig(startAt: Long) {
        var now = startAt
        val fake = FakeSleepTimerController { now }
        var pure: SleepTimerState = SleepTimerState.Idle

        fun start(minutes: Int) {
            fake.start(minutes)
            pure = SleepLogic.start(now, minutes)
        }

        fun extend(minutes: Int) {
            fake.extend(minutes)
            pure = SleepLogic.extend(pure, now, minutes)
        }

        fun extendDefault() {
            fake.extend()
            pure = SleepLogic.extend(pure, now, SleepPresets.EXTEND_MINUTES)
        }

        fun cancel() {
            fake.cancel()
            pure = SleepTimerState.Idle
        }

        fun advance(millis: Long) {
            now += millis
        }

        fun assertSame(label: String) = assertEquals(label, fake.state.value, pure)
    }

    @Test
    fun extendMatchesFakeStepByStep() {
        val rig = Rig(1_000_000L)
        rig.extendDefault()
        rig.assertSame("extend while idle")
        rig.start(120)
        rig.assertSame("start 120")
        rig.extendDefault()
        rig.assertSame("extend once")
        repeat(20) { rig.extendDefault() }
        rig.assertSame("extend to the cap")
        rig.advance(60 * minute)
        rig.extendDefault()
        rig.assertSame("extend after the cap moved")
        rig.advance(300 * minute)
        rig.extendDefault()
        rig.assertSame("extend after the deadline passed")
        rig.cancel()
        rig.extend(60)
        rig.assertSame("extend after cancel")
    }

    @Test
    fun extendMatchesFakeOnARandomWalk() {
        val random = Random(20261021L)
        val rig = Rig(5_000_000L)
        repeat(3_000) { step ->
            when (random.nextInt(12)) {
                0 -> rig.advance(random.nextLong(0L, 90 * minute))
                in 1..2 -> rig.advance(random.nextLong(0L, 5 * minute))
                3 -> rig.start(SleepPresets.minutes[random.nextInt(SleepPresets.minutes.size)])
                4 -> rig.cancel()
                5, 6 -> rig.extend(random.nextInt(0, 61))
                else -> rig.extendDefault()
            }
            rig.assertSame("step $step")
        }
    }
}

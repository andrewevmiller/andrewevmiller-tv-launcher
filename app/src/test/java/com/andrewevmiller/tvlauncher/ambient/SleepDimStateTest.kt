package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepDimStateTest {
    private val fade = 45_000L
    private val t0 = 1_234_567L

    @Test
    fun inactiveStateIsExactlyNormal() {
        val s = SleepDimState(reducedMotion = false)
        assertFalse(s.active)
        assertFalse(s.settled)
        assertEquals(1f, s.level(t0), 0f)
        assertEquals(1f, s.clockLevel(t0), 0f)
        assertFalse("update before start is a no-op", s.update(t0 + fade * 2))
        assertFalse(s.settled)
    }

    @Test
    fun fadeFollowsTheMathFromTheStartTime() {
        val s = SleepDimState(reducedMotion = false)
        s.start(t0)
        assertTrue(s.active)
        assertFalse(s.settled)
        assertEquals(1f, s.level(t0), 0f)
        assertEquals(1f, s.clockLevel(t0), 0f)
        assertEquals(SleepDimMath.level(10_000L, fade, false), s.level(t0 + 10_000L), 0f)
        assertEquals(SleepDimMath.clockLevel(10_000L, fade, false), s.clockLevel(t0 + 10_000L), 0f)
        // Before the start time: still full brightness.
        assertEquals(1f, s.level(t0 - 500L), 0f)
    }

    @Test
    fun settlesOnTheFirstUpdateAtOrAfterTheFadeAndReportsItOnce() {
        val s = SleepDimState(reducedMotion = false)
        s.start(t0)
        assertFalse(s.update(t0 + 44_999L))
        assertFalse(s.settled)
        assertTrue(s.update(t0 + 45_000L))
        assertTrue(s.settled)
        assertFalse("already settled", s.update(t0 + 50_000L))
        assertTrue(s.settled)
    }

    @Test
    fun settledFrameHoldsTheFloorAndNoClockWhateverTheTime() {
        val s = SleepDimState(reducedMotion = false)
        s.start(t0)
        s.update(t0 + fade)
        for (dt in listOf(0L, 10L, fade, fade * 10, Long.MAX_VALUE / 4)) {
            assertEquals(SleepDimMath.FLOOR, s.level(t0 + dt), 0f)
            assertEquals(0f, s.clockLevel(t0 + dt), 0f)
        }
    }

    @Test
    fun reducedMotionIsSettledTheMomentItStartsAtTheDimFrame() {
        val s = SleepDimState(reducedMotion = true)
        s.start(t0)
        assertTrue(s.active)
        assertTrue(s.settled)
        assertEquals(SleepDimMath.FLOOR, s.level(0L), 0f)
        assertEquals(0f, s.clockLevel(0L), 0f)
        assertFalse(s.update(t0 + 1))
    }

    @Test
    fun clearGoesBackToNormalBrightness() {
        val s = SleepDimState(reducedMotion = false)
        s.start(t0)
        s.update(t0 + fade)
        s.clear()
        assertFalse(s.active)
        assertFalse(s.settled)
        assertEquals(1f, s.level(t0 + fade), 0f)
        assertEquals(1f, s.clockLevel(t0 + fade), 0f)
    }

    @Test
    fun startingAgainRestartsTheFade() {
        val s = SleepDimState(reducedMotion = false)
        s.start(t0)
        s.update(t0 + fade)
        assertTrue(s.settled)
        s.start(t0 + 100_000L)
        assertFalse(s.settled)
        assertEquals(1f, s.level(t0 + 100_000L), 0f)
        assertTrue(s.level(t0 + 100_000L + 20_000L) < 1f)
    }

    @Test
    fun aShortFadeSettlesSooner() {
        val s = SleepDimState(reducedMotion = false, fadeMillis = 1_000L)
        s.start(t0)
        assertFalse(s.update(t0 + 999L))
        assertTrue(s.update(t0 + 1_000L))
    }
}

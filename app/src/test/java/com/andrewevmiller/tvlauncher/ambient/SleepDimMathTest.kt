package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepDimMathTest {
    private val fade = SleepDimMath.FADE_MILLIS
    private val floor = SleepDimMath.FLOOR

    private fun level(elapsed: Long, reduced: Boolean = false, fadeMillis: Long = fade) =
        SleepDimMath.level(elapsed, fadeMillis, reduced)

    private fun clock(elapsed: Long, reduced: Boolean = false, fadeMillis: Long = fade) =
        SleepDimMath.clockLevel(elapsed, fadeMillis, reduced)

    @Test
    fun theDocumentedConstants() {
        assertEquals(45_000L, SleepDimMath.FADE_MILLIS)
        assertEquals(0.05f, SleepDimMath.FLOOR, 0f)
        assertEquals(0.6, SleepDimMath.CLOCK_GONE_FRACTION, 0.0)
    }

    @Test
    fun levelStartsAtOneAndEndsAtTheFloor() {
        assertEquals(1f, level(0), 0f)
        assertEquals(floor, level(fade), 0f)
    }

    @Test
    fun levelNeverIncreasesAndStaysBetweenFloorAndOne() {
        var previous = Float.MAX_VALUE
        var t = 0L
        while (t <= fade + 20_000L) {
            val v = level(t)
            assertTrue("level($t) = $v rose above $previous", v <= previous)
            assertTrue("level($t) = $v out of range", v in floor..1f)
            previous = v
            t += 100L
        }
    }

    @Test
    fun levelEasesOutOfBothEnds() {
        // Smoothstep: barely moves in the first and last 5% of the fade, falls fastest in the middle.
        assertTrue(level((fade * 0.05).toLong()) > 0.99f)
        assertTrue(level((fade * 0.95).toLong()) < floor + 0.01f)
        assertEquals(0.5f * (1f + floor), level(fade / 2), 0.001f)
    }

    @Test
    fun levelClampsNegativeAndHugeElapsed() {
        assertEquals(1f, level(-1), 0f)
        assertEquals(1f, level(Long.MIN_VALUE), 0f)
        assertEquals(floor, level(fade + 1), 0f)
        assertEquals(floor, level(Long.MAX_VALUE), 0f)
    }

    @Test
    fun reducedMotionSnapsToTheDimFrameImmediately() {
        assertEquals(floor, level(0, reduced = true), 0f)
        assertEquals(floor, level(-5, reduced = true), 0f)
        assertEquals(floor, level(fade / 2, reduced = true), 0f)
        assertEquals(0f, clock(0, reduced = true), 0f)
    }

    @Test
    fun aCustomFadeLengthScalesTheCurve() {
        assertEquals(1f, level(0, fadeMillis = 1_000L), 0f)
        assertEquals(floor, level(1_000L, fadeMillis = 1_000L), 0f)
        assertEquals(level(fade / 2), level(500L, fadeMillis = 1_000L), 1e-6f)
    }

    @Test
    fun aZeroLengthFadeIsAlreadyDone() {
        assertEquals(floor, level(0, fadeMillis = 0L), 0f)
        assertEquals(0f, clock(0, fadeMillis = 0L), 0f)
        assertTrue(SleepDimMath.isSettled(0, 0L, false))
    }

    @Test
    fun clockStartsAtOneAndIsGoneAtSixtyPercent() {
        assertEquals(1f, clock(0), 0f)
        val gone = (fade * SleepDimMath.CLOCK_GONE_FRACTION).toLong()
        assertEquals(0f, clock(gone), 0f)
        assertTrue(clock(gone - 1000L) > 0f)
        assertEquals(0f, clock(gone + 1), 0f)
        assertEquals(0f, clock(fade), 0f)
        assertEquals(0f, clock(Long.MAX_VALUE), 0f)
        assertEquals(1f, clock(-1), 0f)
    }

    @Test
    fun clockNeverIncreasesAndLeavesBeforeTheDots() {
        var previous = Float.MAX_VALUE
        var t = 0L
        while (t <= fade) {
            val c = clock(t)
            assertTrue("clock($t) = $c rose above $previous", c <= previous)
            assertTrue("clock($t) = $c out of range", c in 0f..1f)
            // The text is never brighter than the dots, relative to where each started.
            assertTrue("clock($t) = $c outshines level $t = ${level(t)}", c <= level(t) + 1e-6f)
            previous = c
            t += 100L
        }
    }

    @Test
    fun settledMeansNothingCanChangeAnyMore() {
        assertFalse(SleepDimMath.isSettled(0, fade, false))
        assertFalse(SleepDimMath.isSettled(fade - 1, fade, false))
        assertTrue(SleepDimMath.isSettled(fade, fade, false))
        assertTrue(SleepDimMath.isSettled(fade + 1, fade, false))
        assertTrue(SleepDimMath.isSettled(Long.MAX_VALUE, fade, false))
        // Before the start it is not settled, it just has not begun.
        assertFalse(SleepDimMath.isSettled(-100, fade, false))
        // Reduced motion is settled from the first moment.
        assertTrue(SleepDimMath.isSettled(0, fade, true))
        assertTrue(SleepDimMath.isSettled(-1, fade, true))
    }

    @Test
    fun levelAndClockAreConstantOnceSettled() {
        var t = fade
        val l = level(t)
        val c = clock(t)
        while (t < fade + 30_000L) {
            assertEquals(l, level(t), 0f)
            assertEquals(c, clock(t), 0f)
            t += 997L
        }
    }

    @Test
    fun scaleColorLeavesFullBrightnessAlone() {
        assertEquals(0xFFB98FD6.toInt(), SleepDimMath.scaleColor(0xFFB98FD6.toInt(), 1f))
        assertEquals(0xFFB98FD6.toInt(), SleepDimMath.scaleColor(0xFFB98FD6.toInt(), 5f))
    }

    @Test
    fun scaleColorGoesToBlackAndKeepsAlpha() {
        assertEquals(0xFF000000.toInt(), SleepDimMath.scaleColor(0xFFB98FD6.toInt(), 0f))
        assertEquals(0xFF000000.toInt(), SleepDimMath.scaleColor(0xFFB98FD6.toInt(), -1f))
        assertEquals(0xFF000000.toInt(), SleepDimMath.scaleColor(0xFFB98FD6.toInt(), Float.NaN))
        assertEquals(0x80000000.toInt(), SleepDimMath.scaleColor(0x80FFFFFF.toInt(), 0f))
    }

    @Test
    fun scaleColorScalesEachChannelAndRounds() {
        // 0x3A = 58; 58 * 0.5 = 29 = 0x1D. 0xFF * 0.5 = 127.5 -> 128 = 0x80.
        assertEquals(0xFF1D1D1D.toInt(), SleepDimMath.scaleColor(0xFF3A3A3A.toInt(), 0.5f))
        assertEquals(0xFF808080.toInt(), SleepDimMath.scaleColor(0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun theFloorLeavesDotsFaintButNotGone() {
        // Dark palette: bg #171716, resting dot #3A3A35, muted #A3A29A, accent #B98FD6.
        val bg = SleepDimMath.scaleColor(0xFF171716.toInt(), floor)
        val muted = SleepDimMath.scaleColor(0xFFA3A29A.toInt(), floor)
        val bgGreen = (bg ushr 8) and 0xFF
        val mutedGreen = (muted ushr 8) and 0xFF
        assertTrue("background should be near-black, was $bgGreen", bgGreen <= 3)
        assertTrue("a lit dot should still be above the background", mutedGreen > bgGreen)
    }
}

package com.andrewevmiller.tvlauncher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandFieldTest {
    private val w = 400f
    private val h = 100f

    @Test
    fun stillFrameHasAFreshRingAndAFadedOne() {
        val rs = BandField.activeRipples(BandField.STILL_TIME_SEC)
        assertEquals(2, rs.size)
        val fresh = rs.first { it.age < 2f }
        assertEquals(BandField.STILL_TIME_SEC, fresh.age, 1e-4f)
        assertEquals(0.63f, fresh.xFrac, 1e-6f)
        assertEquals(0.46f, fresh.yFrac, 1e-6f)
    }

    @Test
    fun rippleAgesStayInsideTheirLifetime() {
        var t = 0f
        while (t < 1300f) {
            val rs = BandField.activeRipples(t)
            // Two rings overlap at any time; a third can appear for a frame at exactly end of life.
            assertTrue("t=$t", rs.size in 1..3)
            for (r in rs) assertTrue("t=$t age=${r.age}", r.age >= 0f && r.age < BandField.LIFE_SEC)
            t += 0.37f
        }
    }

    @Test
    fun originsStayInsideTheCanvasWithAMargin() {
        for (k in 1 until BandField.CYCLE_COUNT) {
            val t = k * BandField.SPAWN_INTERVAL_SEC + 0.1f
            for (r in BandField.activeRipples(t)) {
                assertTrue(r.xFrac in 0.1f..0.9f)
                assertTrue(r.yFrac in 0.14f..0.76f)
            }
        }
    }

    @Test
    fun patternIsDeterministic() {
        assertEquals(BandField.activeRipples(123.4f), BandField.activeRipples(123.4f))
    }

    @Test
    fun patternWrapsSeamlesslyAtTheCycleBoundary() {
        val a = BandField.activeRipples(BandField.STILL_TIME_SEC)
        val b = BandField.activeRipples(BandField.CYCLE_SEC + BandField.STILL_TIME_SEC)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].age, b[i].age, 1e-2f)
            assertEquals(a[i].xFrac, b[i].xFrac, 1e-6f)
            assertEquals(a[i].yFrac, b[i].yFrac, 1e-6f)
        }
    }

    @Test
    fun ringFrontHasTheFadedEnergy() {
        // Age 2 s: front at 110 dp, fade = 1 - 2/5 = 0.6. Origin (200, 50); the dot sits 110 dp to its right.
        val ring = listOf(BandRipple(0.5f, 0.5f, 2f))
        assertEquals(0.6f, BandField.energyAt(310f, 50f, w, h, ring), 1e-4f)
    }

    @Test
    fun dotsFarFromTheRingStayDark() {
        val ring = listOf(BandRipple(0.5f, 0.5f, 2f))
        assertTrue(BandField.energyAt(0f, 0f, w, h, ring) < 1e-3f)
        assertTrue(BandField.energyAt(200f, 50f, w, h, ring) < 1e-3f) // the centre, well behind the front
    }

    @Test
    fun energyAddsAcrossRipplesButClampsToOne() {
        val one = BandRipple(0.5f, 0.5f, 0f)
        val e1 = BandField.energyAt(200f, 50f, w, h, listOf(one))
        val e2 = BandField.energyAt(200f, 50f, w, h, listOf(one, one))
        assertEquals(1f, e1, 1e-4f)
        assertEquals(1f, e2, 0f)
    }

    @Test
    fun noRipplesMeansNoEnergy() {
        assertEquals(0f, BandField.energyAt(10f, 10f, w, h, emptyList()), 0f)
    }

    @Test
    fun energyFadesToNothingAtEndOfLife() {
        val dying = listOf(BandRipple(0.5f, 0.5f, BandField.LIFE_SEC))
        assertEquals(0f, BandField.energyAt(200f + BandField.LIFE_SEC * BandField.SPEED_DP_PER_SEC, 50f, w, h, dying), 1e-6f)
    }

    @Test
    fun timeStartsAtTheStillFrameAndAdvancesInSeconds() {
        assertEquals(BandField.STILL_TIME_SEC, BandField.timeForElapsed(0L), 1e-6f)
        assertEquals(BandField.STILL_TIME_SEC + 2.5f, BandField.timeForElapsed(2_500_000_000L), 1e-5f)
    }

    @Test
    fun timeWrapsAfterOneCycleSoFloatsStayPrecise() {
        val oneCycleNanos = 600_000_000_000L
        assertEquals(BandField.STILL_TIME_SEC, BandField.timeForElapsed(oneCycleNanos), 1e-4f)
        // Ten days of uptime still gives a time within one cycle (+ the start offset).
        val tenDays = 10L * 24 * 3600 * 1_000_000_000L
        val t = BandField.timeForElapsed(tenDays)
        assertTrue(t >= BandField.STILL_TIME_SEC && t < BandField.CYCLE_SEC + BandField.STILL_TIME_SEC)
    }

    @Test
    fun negativeElapsedIsTreatedAsZero() {
        assertEquals(BandField.STILL_TIME_SEC, BandField.timeForElapsed(-5L), 1e-6f)
    }

    @Test
    fun dotRadiusGrowsWithEnergy() {
        assertEquals(BandField.DOT_RADIUS_DP, BandField.dotRadiusDp(0f), 1e-6f)
        assertEquals(BandField.DOT_RADIUS_DP * 2.3f, BandField.dotRadiusDp(1f), 1e-6f)
    }

    @Test
    fun hashIsStableAndUnitIsHalfOpen() {
        assertEquals(BandField.mix32(7), BandField.mix32(7))
        for (k in 0..2000) {
            val u = BandField.unit(BandField.mix32(k))
            assertTrue(u >= 0f && u < 1f)
        }
    }

    @Test
    fun frameCapThinsSixtyHertzToAboutThirtyFps() {
        val cap = BandFrameCap()
        val frame = 16_666_667L
        var accepted = 0
        for (i in 0 until 60) if (cap.accept(i * frame)) accepted++
        assertEquals(30, accepted)
    }

    @Test
    fun frameCapAcceptsTheFirstFrameAndSlowFrames() {
        val cap = BandFrameCap()
        assertTrue(cap.accept(5_000_000_000L))
        assertFalse(cap.accept(5_010_000_000L))
        assertTrue(cap.accept(5_040_000_000L))
        // A 20 fps display (50 ms frames) is never throttled further.
        assertTrue(cap.accept(5_090_000_000L))
        assertTrue(cap.accept(5_140_000_000L))
    }
}

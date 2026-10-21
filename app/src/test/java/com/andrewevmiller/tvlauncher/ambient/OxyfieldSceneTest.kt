package com.andrewevmiller.tvlauncher.ambient

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OxyfieldSceneTest {
    private fun field(seed: Int = 1, w: Int = 1920, h: Int = 1080) = OxyfieldRippleField(Random(seed), w, h)

    @Test
    fun resetStartsWithTheTwoMockupSeedRipples() {
        val f = field()
        f.reset(10_000L)
        assertEquals(2, f.ripples.size)
        assertEquals(10_000L - 2200L, f.ripples[0].startMs)
        assertEquals(10_000L - 500L, f.ripples[1].startMs)
        assertEquals(1200f, f.ripples[0].x, 1e-3f)
        assertEquals(420f, f.ripples[0].y, 1e-3f)
        assertEquals(520f, f.ripples[1].x, 1e-3f)
        assertEquals(700f, f.ripples[1].y, 1e-3f)
    }

    @Test
    fun firstAdvanceSpawnsOneThenWaitsForTheInterval() {
        val f = field()
        f.reset(0L)
        f.advance(0L)
        assertEquals(3, f.ripples.size)
        f.advance(2400L) // exactly the interval: not "more than"
        assertEquals(3, f.ripples.size)
        f.advance(2401L)
        assertEquals(4, f.ripples.size)
    }

    @Test
    fun sameSeedGivesTheSameRipplesDifferentSeedDoesNot() {
        fun run(seed: Int): List<Ripple> {
            val f = field(seed)
            f.reset(0L)
            var t = 0L
            while (t < 30_000L) {
                f.advance(t)
                t += 33L
            }
            return f.ripples.toList()
        }
        assertEquals(run(5), run(5))
        assertNotEquals(run(5), run(6))
    }

    @Test
    fun spawnedRipplesLandInsideTheMockupRegionWithTheMockupSpeedRange() {
        val f = field(3)
        f.reset(0L)
        val seen = ArrayList<Ripple>()
        var t = 0L
        while (t < 120_000L) {
            f.advance(t)
            seen.addAll(f.ripples.filter { it.startMs == t })
            t += 100L
        }
        assertTrue(seen.size > 30)
        for (r in seen) {
            assertTrue("x ${r.x}", r.x in 200f..1720f)
            assertTrue("y ${r.y}", r.y in 120f..960f)
            assertTrue("v ${r.speed}", r.speed in 220f..300f)
            assertEquals(55f, r.width, 1e-4f)
            assertEquals(7f, r.lifeSec, 0f)
        }
    }

    @Test
    fun rippleGeometryScalesWithTheViewWidth() {
        val f = field(3, 3840, 2160)
        f.reset(0L)
        f.advance(0L)
        val spawned = f.ripples.last()
        assertEquals(110f, spawned.width, 1e-3f)
        assertTrue(spawned.speed in 440f..600f)
        assertTrue(spawned.x in 400f..3440f)
        assertTrue(spawned.y in 240f..1920f)
    }

    @Test
    fun deadRipplesAreRemovedAndTheCountStaysBounded() {
        val f = field()
        f.reset(0L)
        var t = 0L
        var maxLive = 0
        while (t < 600_000L) {
            f.advance(t)
            maxLive = maxOf(maxLive, f.ripples.size)
            assertTrue("no expired ripple is kept", f.ripples.none { it.isExpired(t) })
            t += 33L
        }
        assertTrue("max live $maxLive", maxLive <= 5)
        assertTrue(maxLive <= OxyfieldSpec.MAX_RIPPLES)
    }

    @Test
    fun seedRipplesExpireBySevenSecondsAfterTheirStart() {
        val f = field()
        f.reset(0L)
        f.advance(0L)
        f.advance(4900L) // first seed is 7.1 s old
        assertTrue(f.ripples.none { it.startMs == -2200L })
        assertTrue(f.ripples.any { it.startMs == -500L })
    }

    @Test
    fun stillFrameIsIdenticalEveryTimeAndFrozen() {
        val a = oxyfieldStillFrame(1920, 1080)
        val b = oxyfieldStillFrame(1920, 1080)
        assertEquals(a.ripples, b.ripples)
        assertEquals(a.timeMs, b.timeMs)
        assertEquals(2, a.ripples.size)
        // Mockup: painted 2.6 s after the first seed ripple was born.
        assertEquals(2.6f, a.ripples[0].ageSec(a.timeMs), 1e-4f)
        assertTrue(a.ripples.none { it.isExpired(a.timeMs) })
    }

    @Test
    fun stillFrameActuallyLightsSomeDots() {
        val frame = oxyfieldStillFrame(1920, 1080)
        val g = OxyfieldGrid(1920, 1080)
        var lit = 0
        for (row in 0 until g.rows) for (col in 0 until g.columns) {
            if (rippleEnergy(g.x(col), g.y(row), frame.ripples, frame.timeMs) >= OxyfieldSpec.IDLE_ENERGY) lit++
        }
        assertTrue("lit $lit of ${g.dotCount}", lit in 20 until g.dotCount)
    }
}

package com.andrewevmiller.tvlauncher.ambient

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OxyfieldMathTest {
    // Arbitrary test colours; the real palette comes from DarkFieldColors in the Android layer.
    private val palette = OxyfieldPalette(
        bg = 0xFF000000.toInt(),
        dot = 0xFF202020.toInt(),
        muted = 0xFF808080.toInt(),
        accent = 0xFFF00000.toInt(),
        ink = 0xFFFFFFFF.toInt(),
    )

    // --- grid ---

    @Test
    fun gridAt1080pMatchesTheMockup() {
        val g = OxyfieldGrid(1920, 1080)
        assertEquals(1f, g.scale, 0f)
        assertEquals(44f, g.spacing, 0f)
        assertEquals(44, g.columns)
        assertEquals(25, g.rows)
        assertEquals(22f, g.x(0), 0f)
        assertEquals(22f, g.y(0), 0f)
        assertEquals(22f + 43 * 44f, g.x(43), 0f)
    }

    @Test
    fun gridScalesWithWidthSoA4kViewGetsTheSameDotCount() {
        val g = OxyfieldGrid(3840, 2160)
        assertEquals(2f, g.scale, 0f)
        assertEquals(88f, g.spacing, 0f)
        assertEquals(44, g.columns)
        assertEquals(25, g.rows)
        assertEquals(2.2f * 2f, g.dotRadius, 1e-6f)
    }

    @Test
    fun everyDotIsInsideTheView() {
        for ((w, h) in listOf(1920 to 1080, 1280 to 720, 3840 to 2160, 1000 to 1000, 700 to 400)) {
            val g = OxyfieldGrid(w, h)
            assertTrue(g.x(g.columns - 1) < w)
            assertTrue(g.y(g.rows - 1) < h)
            // The next dot would fall outside, so no dots are missing.
            assertTrue(g.x(g.columns) >= w)
            assertTrue(g.y(g.rows) >= h)
        }
    }

    @Test
    fun emptyViewHasNoDotsAndDoesNotThrow() {
        val g = OxyfieldGrid(0, 0)
        assertEquals(0, g.dotCount)
        assertEquals(0, OxyfieldGrid(-5, 100).columns)
    }

    // --- ripples ---

    private fun ripple(startMs: Long = 0L) =
        Ripple(x = 500f, y = 400f, startMs = startMs, speed = 200f, width = 50f, lifeSec = 7f)

    @Test
    fun energyPeaksOnTheRingAndFollowsTheFade() {
        val r = ripple()
        // 2 s old: ring radius 400 px, fade 1 - 2/7.
        val onRing = r.energyAt(500f + 400f, 400f, 2000L)
        assertEquals(1f - 2f / 7f, onRing, 1e-4f)
        val offRing = r.energyAt(500f + 400f + 50f, 400f, 2000L)
        assertEquals(onRing * exp(-0.5f), offRing, 1e-4f)
    }

    @Test
    fun energyIsZeroBeforeBirthAfterDeathAndFarFromTheRing() {
        val r = ripple(startMs = 1000L)
        assertEquals(0f, r.energyAt(500f, 400f, 500L), 0f)
        assertEquals(0f, r.energyAt(500f + 1400f, 400f, 1000L + 7000L), 0f)
        assertEquals(0f, r.energyAt(500f, 400f, 1000L + 2000L), 0f) // centre, ring is 400 px away
    }

    @Test
    fun rippleExpiresAtItsLife() {
        val r = ripple(startMs = 100L)
        assertFalse(r.isExpired(100L + 6999L))
        assertTrue(r.isExpired(100L + 7000L))
        assertTrue(r.isExpired(100L + 9000L))
    }

    @Test
    fun degenerateRipplesGiveZeroNotNaN() {
        val flat = Ripple(0f, 0f, 0L, speed = 100f, width = 0f, lifeSec = 7f)
        val dead = Ripple(0f, 0f, 0L, speed = 100f, width = 10f, lifeSec = 0f)
        assertEquals(0f, flat.energyAt(0f, 0f, 1000L), 0f)
        assertEquals(0f, dead.energyAt(0f, 0f, 0L), 0f)
    }

    @Test
    fun clippingMatchesTheUnclippedMockupFormulaWithinOneThousandth() {
        val rnd = Random(7)
        val ripples = (0 until 4).map {
            Ripple(
                x = rnd.nextFloat() * 1920f,
                y = rnd.nextFloat() * 1080f,
                startMs = (it * 1500).toLong(),
                speed = 220f + rnd.nextFloat() * 80f,
                width = 55f,
                lifeSec = 7f,
            )
        }
        val now = 6000L
        for (i in 0 until 2000) {
            val x = rnd.nextFloat() * 1920f
            val y = rnd.nextFloat() * 1080f
            var ref = 0.0
            for (r in ripples) {
                val age = (now - r.startMs) / 1000.0
                if (age < 0 || age >= r.lifeSec) continue
                val d = hypot((x - r.x).toDouble(), (y - r.y).toDouble())
                val fade = maxOf(0.0, 1 - age / r.lifeSec)
                ref += exp(-((d - age * r.speed) * (d - age * r.speed)) / (2 * r.width * r.width)) * fade
            }
            ref = minOf(1.0, ref)
            assertEquals(ref, rippleEnergy(x, y, ripples, now).toDouble(), 1e-3)
        }
    }

    @Test
    fun totalEnergyStaysWithinZeroAndOneAndIsFinite() {
        val rnd = Random(99)
        // Many overlapping ripples at the same spot would sum well past 1 without the clamp.
        val ripples = (0 until 8).map { Ripple(960f, 540f, 0L, 250f, 55f, 7f) }
        for (i in 0 until 5000) {
            val e = rippleEnergy(rnd.nextFloat() * 1920f, rnd.nextFloat() * 1080f, ripples, rnd.nextInt(-500, 9000).toLong())
            assertTrue("energy $e", e >= 0f && e <= 1f && !e.isNaN())
        }
        assertEquals(0f, rippleEnergy(0f, 0f, emptyList(), 0L), 0f)
    }

    // --- colour ---

    @Test
    fun lerpColorHitsTheEndpointsAndMixesPerChannel() {
        val a = 0xFF000000.toInt()
        val b = 0xFFFFFFFF.toInt()
        assertEquals(a, lerpColor(a, b, 0f))
        assertEquals(b, lerpColor(a, b, 1f))
        assertEquals(a, lerpColor(a, b, -3f))
        assertEquals(b, lerpColor(a, b, 3f))
        assertEquals(0xFF808080.toInt(), lerpColor(a, b, 0.5f))
        assertEquals(0xFF102030.toInt(), lerpColor(0xFF102030.toInt(), 0xFF102030.toInt(), 0.37f))
    }

    @Test
    fun dotColorRunsLineToMutedToAccent() {
        assertEquals(palette.dot, oxyfieldDotColor(0f, palette))
        assertEquals(palette.muted, oxyfieldDotColor(0.5f, palette))
        assertEquals(palette.accent, oxyfieldDotColor(1f, palette))
        assertEquals(lerpColor(palette.dot, palette.muted, 0.5f), oxyfieldDotColor(0.25f, palette))
        assertEquals(lerpColor(palette.muted, palette.accent, 0.5f), oxyfieldDotColor(0.75f, palette))
    }

    @Test
    fun accentOnlyAppearsAboveHalfEnergy() {
        // Below 0.5 the red channel never exceeds muted's, so no accent tint leaks into resting/faint dots.
        val mutedRed = (palette.muted ushr 16) and 0xFF
        for (i in 0..50) {
            val e = i / 100f
            assertTrue((oxyfieldDotColor(e, palette) ushr 16 and 0xFF) <= mutedRed)
        }
        assertTrue((oxyfieldDotColor(0.9f, palette) ushr 16 and 0xFF) > mutedRed)
    }

    @Test
    fun dotsGrowWithEnergy() {
        assertEquals(2.2f, oxyfieldDotRadius(0f, 2.2f), 1e-6f)
        assertEquals(2.2f * 2.3f, oxyfieldDotRadius(1f, 2.2f), 1e-5f)
        assertTrue(oxyfieldDotRadius(0.4f, 2.2f) < oxyfieldDotRadius(0.8f, 2.2f))
    }

    // --- clock layout ---

    @Test
    fun clockStaysInsideTheSafeArea() {
        for ((w, h) in listOf(1920 to 1080, 3840 to 2160, 1280 to 720)) {
            val c = oxyfieldClockLayout(w, h)
            assertTrue(c.left >= w * 0.05f - 0.01f)
            assertTrue(c.dateBaseline <= h * 0.95f)
            assertTrue(c.timeBaseline < c.dateBaseline)
            // Generous glyph bounds: ascent up to one full text size above the baseline.
            assertTrue(c.timeBaseline - c.timeSize >= h * 0.05f)
            assertTrue(abs(c.timeSize / w - 80f / 1920f) < 1e-6f)
        }
    }
}

package com.andrewevmiller.tvlauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.hypot

class DotEnergyTest {
    private val ripple = Ripple(300f, 100f, 0f, 110f, 24f, 5f)

    /** The original formula, with no skipping. */
    private fun full(x: Float, y: Float, t: Float, rs: List<Ripple>): Float {
        var e = 0f
        for (r in rs) {
            val age = t - r.t0
            val diff = hypot(x - r.x, y - r.y) - age * r.speed
            e += exp(-(diff * diff) / (2 * r.width * r.width)) * maxOf(0f, 1 - age / r.life)
        }
        return minOf(1f, e)
    }

    @Test fun skippingFarRipplesChangesNothingVisible() {
        val rs = listOf(ripple, Ripple(100f, 50f, 1f, 110f, 24f, 5f))
        var worst = 0f
        var x = 9f
        while (x < 600f) { var y = 9f; while (y < 216f) { for (t in listOf(0.3f, 1.7f, 3f, 4.9f)) worst = maxOf(worst, kotlin.math.abs(full(x, y, t, rs) - dotEnergy(x, y, t, rs))); y += 18f }; x += 18f }
        assertTrue("worst difference $worst", worst < 0.002f)
    }

    @Test fun aMajorityOfDotsAreUnlitAtMostMoments() {
        var lit = 0; var total = 0
        var x = 9f
        while (x < 600f) { var y = 9f; while (y < 216f) { total++; if (dotEnergy(x, y, 3.5f, listOf(ripple)) > 0.03f) lit++; y += 18f }; x += 18f }
        assertTrue("$lit of $total lit", lit < total / 2)
    }

    @Test fun anExpiredRippleContributesNothing() = assertEquals(0f, dotEnergy(300f, 100f, 6f, listOf(ripple)), 0f)
}

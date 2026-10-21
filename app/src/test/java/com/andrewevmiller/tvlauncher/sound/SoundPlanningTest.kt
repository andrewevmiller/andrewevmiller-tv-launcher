package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPlanningTest {

    @Test
    fun tickRowsClampToTheRenderedRange() {
        assertEquals(0, clampTickRow(-7))
        assertEquals(0, clampTickRow(0))
        assertEquals(5, clampTickRow(5))
        assertEquals(11, clampTickRow(11))
        assertEquals(11, clampTickRow(12))
        assertEquals(11, clampTickRow(Int.MAX_VALUE))
        assertEquals(0, clampTickRow(Int.MIN_VALUE))
    }

    @Test
    fun humanizeSpansPlusMinusFiveCentsAndTenPercent() {
        val low = humanize(0.0, 0.0)
        assertEquals(rateForCents(-5.0), low.rate, 1e-12)
        assertEquals(0.9f, low.gain, 1e-6f)

        val mid = humanize(0.5, 0.5)
        assertEquals(1.0, mid.rate, 1e-12)
        assertEquals(1.0f, mid.gain, 1e-6f)

        val high = humanize(0.999999, 0.999999)
        assertEquals(rateForCents(5.0), high.rate, 1e-5)
        assertEquals(1.1f, high.gain, 1e-4f)

        // uniform input gives uniform output in range
        for (i in 0..100) {
            val u = i / 101.0
            val h = humanize(u, 1.0 - u)
            assertTrue(h.rate >= rateForCents(-5.0) - 1e-12 && h.rate <= rateForCents(5.0))
            assertTrue(h.gain >= 0.9f - 1e-6f && h.gain <= 1.1f)
        }
    }

    @Test
    fun launchChoiceFollowsTheOverrides() {
        val overrides = mapOf(
            "com.muted" to MotifOverride(muted = true, rerollCount = 4),
            "com.rerolled" to MotifOverride(rerollCount = 3),
            "com.weird" to MotifOverride(rerollCount = -2),
        )
        assertEquals(LaunchChoice.Muted, launchChoice("com.muted", overrides))
        assertEquals(LaunchChoice.Motif(3), launchChoice("com.rerolled", overrides))
        assertEquals(LaunchChoice.Motif(0), launchChoice("com.untouched", overrides))
        assertEquals(LaunchChoice.Motif(0), launchChoice("com.weird", overrides))
        assertEquals(LaunchChoice.Motif(0), launchChoice("com.anything", emptyMap()))
    }

    @Test
    fun onlyRerollCountChangesTriggerARerender() {
        val a = mapOf("p" to MotifOverride(rerollCount = 1), "q" to MotifOverride(rerollCount = 2))
        assertEquals(emptySet<String>(), rerollChanged(a, a))
        assertEquals(setOf("p"), rerollChanged(a, a + ("p" to MotifOverride(rerollCount = 2))))
        assertEquals(setOf("q"), rerollChanged(a, a - "q")) // reset back to count 0
        assertEquals(setOf("n"), rerollChanged(a, a + ("n" to MotifOverride(rerollCount = 1))))
        assertEquals(emptySet<String>(), rerollChanged(a, a + ("p" to MotifOverride(rerollCount = 1, muted = true))))
        assertEquals(setOf("p", "q"), rerollChanged(a, emptyMap()))
        assertEquals(emptySet<String>(), rerollChanged(emptyMap(), mapOf("m" to MotifOverride(muted = true))))
    }

    @Test
    fun previewIsTwoTicksSelectAndPlexAtThePlannedTimes() {
        assertEquals(
            listOf(
                PreviewStep(0, PreviewSound.TICK),
                PreviewStep(110, PreviewSound.TICK),
                PreviewStep(240, PreviewSound.SELECT),
                PreviewStep(480, PreviewSound.MOTIF),
            ),
            PREVIEW_STEPS,
        )
        assertEquals(2, PREVIEW_TICK_ROW)
        assertEquals("com.plexapp.android", PREVIEW_PACKAGE)
        assertEquals(AppCategory.VIDEO, PREVIEW_CATEGORY)
        assertEquals(800L, PREVIEW_MAX_WAIT_MS)
        assertEquals(0, msToSamples(0))
        assertEquals(5280, msToSamples(110))
        assertEquals(11520, msToSamples(240))
        assertEquals(23040, msToSamples(480))
        assertEquals(24, msToSamples(1, 24000))
    }

    @Test
    fun throttledValueRereadsAtMostOncePerInterval() {
        var reads = 0
        var value = true
        val v = ThrottledValue(1000L) {
            reads++
            value
        }
        assertEquals(true, v.get(10_000))
        assertEquals(1, reads)
        value = false
        assertEquals(true, v.get(10_500)) // cached
        assertEquals(true, v.get(10_999))
        assertEquals(1, reads)
        assertEquals(false, v.get(11_000)) // a second has passed
        assertEquals(2, reads)
        assertEquals(false, v.get(11_100))
        assertEquals(2, reads)
        value = true
        assertEquals(true, v.get(5_000)) // the clock went backwards: read again rather than trust the cache
        assertEquals(3, reads)
    }
}

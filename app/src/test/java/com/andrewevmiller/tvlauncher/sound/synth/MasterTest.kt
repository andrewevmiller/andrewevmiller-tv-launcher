package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterTest {
    private val sr = 48_000

    private fun comp() = DynamicsCompressor(sr)

    private fun run(c: DynamicsCompressor, x: DoubleArray): DoubleArray {
        val padded = Master.paddedLength(x.size + c.preDelayFrames)
        val buf = x.copyOf(padded)
        c.process(buf, buf, padded)
        return buf
    }

    private fun peakBetween(x: DoubleArray, from: Int, to: Int): Double {
        var p = 0.0
        for (i in from until to) p = maxOf(p, abs(x[i]))
        return p
    }

    @Test
    fun staticCurveAndAutomaticMakeupGain() {
        val c = comp()
        println(
            "compressor: staticGainAt0dBFS=${"%.4f".format(c.staticGainAt0dBFS)} (${"%.2f".format(20 * log10(c.staticGainAt0dBFS))} dB), " +
                "makeup=${"%.4f".format(c.makeupGain)} (${"%.2f".format(20 * log10(c.makeupGain))} dB)",
        )
        // linear up to the -20 dB threshold
        assertEquals(0.05, c.saturate(0.05), 1e-12)
        assertEquals(0.1, c.saturate(0.1 - 1e-9), 1e-6)
        // continuous through the soft knee (threshold -20 dB, knee 30 dB, so the knee ends at +10 dB)
        val kneeEnd = Math.pow(10.0, 10.0 / 20.0)
        assertEquals(c.saturate(kneeEnd * (1 - 1e-9)), c.saturate(kneeEnd * (1 + 1e-9)), 1e-6)
        // beyond the knee the slope is 1/ratio = 1/6 in dB/dB
        val x1 = kneeEnd * 2
        val x2 = kneeEnd * 4
        val slope = 20 * log10(c.saturate(x2) / c.saturate(x1)) / (20 * log10(x2 / x1))
        assertEquals(1.0 / 6.0, slope, 1e-6)
        // monotonic and compressive
        var prev = 0.0
        var x = 0.001
        while (x < 4.0) {
            val y = c.saturate(x)
            assertTrue(y >= prev)
            assertTrue(y <= x + 1e-12)
            prev = y
            x *= 1.05
        }
        assertEquals(1.0 / c.staticGainAt0dBFS, Math.pow(c.makeupGain, 1.0 / 0.6), 1e-9)
        assertTrue(c.staticGainAt0dBFS in 0.5..0.95)
        assertTrue(c.makeupGain in 1.0..1.5)
    }

    @Test
    fun adaptiveReleasePolynomialPassesThroughItsFourZones() {
        val c = comp()
        val release = 0.12 * sr
        assertEquals(release * 0.09, c.releasePolynomial(0.0), 1e-6)
        assertEquals(release * 0.16, c.releasePolynomial(1.0), 1e-6)
        assertEquals(release * 0.42, c.releasePolynomial(2.0), 1e-6)
        assertEquals(release * 0.98, c.releasePolynomial(3.0), 1e-6)
    }

    @Test
    fun belowThresholdOnlyMakeupGainAndLookAheadApply() {
        val c = comp()
        val n = sr / 2
        val amp = 0.01 // -40 dBFS
        val x = DoubleArray(n) { amp * sin(2.0 * PI * 1000.0 * it / sr) }
        val y = run(c, x)
        val d = c.preDelayFrames
        assertEquals(288, d)
        val outPeak = peakBetween(y, n / 2 + d, n + d - 1000)
        assertEquals(amp * c.makeupGain, outPeak, 0.01 * amp * c.makeupGain)
        // look-ahead: an impulse comes out exactly preDelayFrames later
        val imp = DoubleArray(4000)
        imp[1000] = 0.05
        val yi = run(comp(), imp)
        var at = 0
        for (i in yi.indices) if (abs(yi[i]) > abs(yi[at])) at = i
        assertEquals(1000 + d, at)
    }

    @Test
    fun loudSineIsCompressedByTheStaticCurveWithinTwoDb() {
        val c = comp()
        val n = sr
        for (amp in doubleArrayOf(0.25, 0.5, 1.0)) {
            val c2 = comp()
            val x = DoubleArray(n) { amp * sin(2.0 * PI * 1000.0 * it / sr) }
            val y = run(c2, x)
            val outPeak = peakBetween(y, n / 2 + c2.preDelayFrames, n)
            val expected = c2.saturate(amp) * c2.makeupGain
            val errDb = 20 * log10(outPeak / expected)
            println("compressor sine amp $amp: out ${"%.3f".format(outPeak)}, static curve ${"%.3f".format(expected)} (${"%.2f".format(errDb)} dB)")
            assertEquals("amp $amp", 0.0, errDb, 2.0)
        }
        assertTrue(c.preDelayFrames > 0)
    }

    @Test
    fun attackReducesGainQuicklyAndReleaseRecovers() {
        val c = comp()
        // 60 ms of a loud tone, then a quiet one
        val loud = 0.9
        val quiet = 0.02
        val n = sr
        val tone = DoubleArray(n) {
            val a = if (it < 0.06 * sr) loud else quiet
            a * sin(2.0 * PI * 500.0 * it / sr)
        }
        val y = run(c, tone)
        val d = c.preDelayFrames
        fun gainAt(t: Double): Double {
            val i = (t * sr).toInt() + d
            val amp = if (t < 0.06) loud else quiet
            return peakBetween(y, i, i + 96) / (amp * c.makeupGain)
        }
        val g20ms = gainAt(0.02)
        val g50ms = gainAt(0.05)
        val gRecovered = gainAt(0.8)
        println("compressor gain: 20 ms ${"%.3f".format(g20ms)}, 50 ms ${"%.3f".format(g50ms)}, recovered ${"%.3f".format(gRecovered)}")
        assertTrue("reduces", g50ms < 0.85)
        assertTrue("attack is in progress or done by 20 ms", g20ms < 1.0)
        assertEquals("release recovers", 1.0, gRecovered, 0.05)
        assertTrue(TestDsp.allFinite(y))
    }

    @Test
    fun masterProcessIsTransparentBelowTheCeilingAndBoundedAbove() {
        val info = RenderInfo()
        // quiet: the ceiling does nothing
        val n = 4800
        val quiet = DoubleArray(Master.paddedLength(n + 288 + 2400)) { if (it < n) 0.05 * sin(2.0 * PI * 800.0 * it / sr) else 0.0 }
        val out = Master.process(quiet, n, sr, info)
        assertFalse(info.ceilingApplied)
        assertTrue(info.peakBeforeCeiling < Master.CEILING)
        assertEquals(n, out.size)

        // very loud: compressed, then held under -1 dBFS by the soft ceiling
        val loud = DoubleArray(Master.paddedLength(n + 288 + 2400)) { if (it < n) 40.0 * sin(2.0 * PI * 800.0 * it / sr) else 0.0 }
        val outLoud = Master.process(loud, n, sr, info)
        assertTrue(info.ceilingApplied)
        assertTrue(info.peakBeforeCeiling > Master.CEILING)
        assertTrue(TestDsp.peak(outLoud) <= Master.CEILING + 1e-6)
        assertTrue(TestDsp.peak(outLoud) > 0.8)
        assertTrue(TestDsp.allFinite(outLoud))
    }

    @Test
    fun highpassRemovesDcAndRumbleBeforeTheCompressor() {
        val n = sr
        val dc = DoubleArray(Master.paddedLength(n + 288 + 2400)) { if (it < n) 0.05 else 0.0 }
        val out = Master.process(dc, n, sr, null)
        // after the 70 Hz high-pass settles, the constant is gone
        var tail = 0.0
        for (i in n / 2 until n - 100) tail = maxOf(tail, abs(out[i].toDouble()))
        assertTrue("dc tail $tail", tail < 1e-4)
    }
}

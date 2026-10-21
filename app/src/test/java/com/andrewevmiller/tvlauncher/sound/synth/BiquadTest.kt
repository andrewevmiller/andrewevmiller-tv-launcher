package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {
    private val sr = 48_000

    /** Steady-state amplitude of `filter` for a unit sine at [f]. */
    private fun gainAt(f: Double, setup: (Biquad) -> Unit): Double {
        val b = Biquad()
        setup(b)
        val n = 2 * sr
        var peak = 0.0
        for (i in 0 until n) {
            val y = b.process(sin(2.0 * PI * f * i / sr))
            if (i > n - sr / 2 && abs(y) > peak) peak = abs(y)
        }
        return peak
    }

    @Test
    fun lowpassQIsInDecibelsLikeWebAudio() {
        // RBJ: the gain at the cutoff equals the linear Q = 10^(Q_dB/20)
        for (qDb in doubleArrayOf(0.0, 1.5, 6.0, 12.0)) {
            val g = gainAt(1000.0) { it.setLowpass(1000.0, qDb, sr) }
            assertEquals("Q $qDb dB", Math.pow(10.0, qDb / 20.0), g, 0.02 * Math.pow(10.0, qDb / 20.0))
        }
        assertEquals(1.0, gainAt(50.0) { it.setLowpass(2000.0, 6.0, sr) }, 0.01)
        assertTrue(gainAt(12000.0) { it.setLowpass(1000.0, 1.5, sr) } < 0.02) // 12 octaves... well down
    }

    @Test
    fun highpassMasterSettings() {
        // the master: 70 Hz, Q 1 dB
        assertEquals(1.0, gainAt(1000.0) { it.setHighpass(70.0, 1.0, sr) }, 0.01)
        val atCorner = gainAt(70.0) { it.setHighpass(70.0, 1.0, sr) }
        assertEquals(Math.pow(10.0, 1.0 / 20.0), atCorner, 0.03)
        assertTrue(gainAt(10.0) { it.setHighpass(70.0, 1.0, sr) } < 0.03)
        val b = Biquad()
        b.setHighpass(70.0, 1.0, sr)
        var y = 1.0
        for (i in 0 until sr) y = b.process(1.0)
        assertEquals("DC is removed", 0.0, y, 1e-6)
    }

    @Test
    fun bandpassQIsLinearWithUnityPeak() {
        val f = 440.0
        assertEquals(1.0, gainAt(f) { it.setBandpass(f, 30.0, sr) }, 0.02)
        // -3 dB points at f * (1 +- 1/(2Q))
        assertEquals(1.0 / sqrt(2.0), gainAt(f * (1 + 1.0 / 60)) { it.setBandpass(f, 30.0, sr) }, 0.03)
        assertTrue(gainAt(f * 1.5) { it.setBandpass(f, 30.0, sr) } < 0.1)
        // the click filter: 3200 Hz, Q 1.2
        assertEquals(1.0, gainAt(3200.0) { it.setBandpass(3200.0, 1.2, sr) }, 0.02)
    }

    @Test
    fun sweepsStayStableOnNoise() {
        val rng = XorShift64(3L)
        val n = sr / 2
        val sweepSamples = 0.05 * sr
        val cases = listOf(
            Triple(9000.0, 60.0, 1.5),
            Triple(9000.0, 60.0, 7.0),
            Triple(9000.0, 60.0, 20.0),
            Triple(60.0, 9000.0, 20.0),
            Triple(7000.0, 2500.0, 3.0),
            Triple(600.0, 4000.0, 4.0),
        )
        for ((from, to, q) in cases) {
            val buf = DoubleArray(n) { rng.nextSigned() }
            Biquad().sweep(Biquad.Mode.LOWPASS_DB, buf, 0, n, from, to, sweepSamples, q, sr)
            assertTrue("finite $from->$to q=$q", TestDsp.allFinite(buf))
            val peak = buf.maxOf { abs(it) }
            assertTrue("bounded $from->$to q=$q (peak $peak)", peak < 40.0)
        }
        // after a sweep down to 60 Hz the output is nearly silent: noise energy only in a few Hz
        val buf = DoubleArray(n) { rng.nextSigned() }
        Biquad().sweep(Biquad.Mode.LOWPASS_DB, buf, 0, n, 9000.0, 60.0, sweepSamples, 7.0, sr)
        var tail = 0.0
        for (i in n - 4800 until n) tail = maxOf(tail, abs(buf[i]))
        assertTrue("settled at 60 Hz, tail peak $tail", tail < 0.4)
        // band-pass follows a glide down to 50 Hz without blowing up
        val bp = DoubleArray(n) { rng.nextSigned() }
        Biquad().sweep(Biquad.Mode.BANDPASS_LINEAR, bp, 0, n, 1300.0, 50.0, 0.13 * sr, 30.0, sr)
        assertTrue(TestDsp.allFinite(bp))
        assertTrue(bp.maxOf { abs(it) } < 5.0)
    }

    @Test
    fun sweepFollowsTheExponentialCutoff() {
        // a sine at 1 kHz is passed while the cutoff is far above it and removed once the cutoff is far below
        val n = sr / 2
        val buf = DoubleArray(n) { sin(2.0 * PI * 1000.0 * it / sr) }
        Biquad().sweep(Biquad.Mode.LOWPASS_DB, buf, 0, n, 9000.0, 100.0, 0.25 * sr, 1.5, sr)
        var early = 0.0
        for (i in 0 until 480) early = maxOf(early, abs(buf[i]))
        var late = 0.0
        for (i in n - 480 until n) late = maxOf(late, abs(buf[i]))
        assertTrue("early $early", early > 0.9)
        assertTrue("late $late", late < 0.02)
        assertTrue("log10 sanity", log10(late) < -1.5)
    }
}

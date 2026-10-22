package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OscillatorsTest {
    private val sr = 48_000

    private fun render(type: OscType, freq: Double, seconds: Double = 2.0, cents: Double = 0.0): DoubleArray {
        val n = (seconds * sr).toInt()
        val out = DoubleArray(n)
        Oscillators.add(type, out, 0, n, freq, Double.NaN, 0.0, cents, sr, 1.0)
        return out
    }

    @Test
    fun measuredFrequencyIsWithinHalfACent() {
        val freqs = doubleArrayOf(110.0, 440.0, 1320.0, 4000.0)
        for (type in OscType.values()) {
            for (f in freqs) {
                val x = render(type, f)
                val err = TestDsp.cents(TestDsp.zeroCrossingFrequency(x, sr), f)
                println("freq $type $f Hz: ${"%.4f".format(err)} cents")
                assertTrue("$type $f Hz off by $err cents", abs(err) < 0.5)
            }
        }
    }

    @Test
    fun detunedVoicesLandAtTheExpectedCents() {
        for (type in OscType.values()) {
            for (c in doubleArrayOf(-22.0, -9.0, 9.0, 14.0, 22.0)) {
                val x = render(type, 440.0, cents = c)
                val got = TestDsp.cents(TestDsp.zeroCrossingFrequency(x, sr), 440.0)
                assertEquals("$type detune $c", c, got, 0.5)
            }
        }
    }

    @Test
    fun voiceSpreadIsLinearAcrossVoices() {
        assertEquals(0.0, VoiceRenderer.spreadCents(0, 1, 9.0), 0.0)
        assertEquals(-9.0, VoiceRenderer.spreadCents(0, 2, 9.0), 1e-12)
        assertEquals(9.0, VoiceRenderer.spreadCents(1, 2, 9.0), 1e-12)
        assertEquals(-10.0, VoiceRenderer.spreadCents(0, 3, 10.0), 1e-12)
        assertEquals(0.0, VoiceRenderer.spreadCents(1, 3, 10.0), 1e-12)
        assertEquals(10.0, VoiceRenderer.spreadCents(2, 3, 10.0), 1e-12)
        val saw = (0 until 7).map { VoiceRenderer.spreadCents(it, 7, 22.0) }
        assertEquals(-22.0, saw.first(), 1e-12)
        assertEquals(22.0, saw.last(), 1e-12)
        for (i in 1 until 7) assertEquals(44.0 / 6, saw[i] - saw[i - 1], 1e-9)
    }

    @Test
    fun amplitudeAndStartPhase() {
        for (type in OscType.values()) {
            val x = render(type, 440.0, seconds = 0.5)
            assertTrue(TestDsp.allFinite(x))
            val peak = x.maxOf { abs(it) }
            assertEquals("$type peak", 1.0, peak, 0.02)
        }
        // sine, triangle and saw start at zero and rise, like Web Audio's oscillators
        for (type in listOf(OscType.SINE, OscType.TRIANGLE, OscType.SAW)) {
            val x = render(type, 220.0, seconds = 0.05)
            assertEquals("$type starts at 0", 0.0, x[0], 0.02)
            assertTrue("$type rises", x[10] > x[0])
        }
    }

    @Test
    fun exponentialGlideHitsBothEndsAndHolds() {
        val dur = 0.5
        val n = (1.0 * sr).toInt()
        for (type in OscType.values()) {
            val out = DoubleArray(n)
            Oscillators.add(type, out, 0, n, 200.0, 400.0, dur * sr, 0.0, sr, 1.0)
            // after the glide ends the frequency holds at glideTo
            val tail = out.copyOfRange((0.55 * sr).toInt(), n)
            val f = TestDsp.zeroCrossingFrequency(tail, sr)
            assertEquals("$type end", 400.0, f, 400.0 * 0.001)
            // octave glide: half-way through the frequency is the geometric mean, 283 Hz
            val mid = out.copyOfRange((0.2 * sr).toInt(), (0.3 * sr).toInt())
            val fm = TestDsp.zeroCrossingFrequency(mid, sr)
            assertEquals("$type mid", 200.0 * 2.0.pow(0.5), fm, 8.0)
        }
    }

    @Test
    fun startOffsetKeepsTheGlideLockedToTheNote() {
        // A voice that starts 1 ms late still sits on the note's glide curve (the mockup ramps in absolute time).
        val n = (0.3 * sr).toInt()
        val a = DoubleArray(n)
        Oscillators.add(OscType.SINE, a, 0, n, 100.0, 200.0, 0.2 * sr, 0.0, sr, 1.0)
        val b = DoubleArray(n)
        Oscillators.add(OscType.SINE, b, 48, n, 100.0, 200.0, 0.2 * sr, 0.0, sr, 1.0)
        val fa = TestDsp.zeroCrossingFrequency(a.copyOfRange((0.21 * sr).toInt(), n), sr)
        val fb = TestDsp.zeroCrossingFrequency(b.copyOfRange((0.21 * sr).toInt(), n), sr)
        assertEquals(200.0, fa, 0.2)
        assertEquals(200.0, fb, 0.2)
        for (i in 0 until 48) assertEquals(0.0, b[i], 0.0)
    }

    @Test
    fun noNanOrInfAnywhere() {
        for (type in OscType.values()) {
            for (f in doubleArrayOf(20.0, 440.0, 5000.0, 12000.0, 23000.0)) {
                assertTrue("$type $f", TestDsp.allFinite(render(type, f, seconds = 0.2)))
            }
            val out = DoubleArray(sr)
            Oscillators.add(type, out, 0, sr, 8000.0, 50.0, 0.5 * sr, 22.0, sr, 0.4)
            assertTrue(TestDsp.allFinite(out))
        }
    }

    /** Energy that is not on a harmonic of f0, as a fraction of total: the aliasing, plus a floor of window leakage. */
    private fun inharmonicFraction(x: DoubleArray, f0: Double): Double {
        val seg = 32768
        val spec = TestDsp.welch(x, seg)
        val binHz = sr.toDouble() / seg
        var total = 0.0
        var other = 0.0
        val maxHarmonic = (sr / 2 / f0).toInt()
        for (k in 3 until spec.size) {
            total += spec[k]
            val h = k * binHz / f0
            val nearest = Math.round(h).toInt()
            val offBins = abs(h - nearest) * f0 / binHz
            val onHarmonic = nearest in 1..maxHarmonic && offBins < 8.0
            if (!onHarmonic) other += spec[k]
        }
        return other / total
    }

    @Test
    fun sawAndSquareAliasFarLessThanNaive() {
        val n = 65536
        for (type in listOf(OscType.SAW, OscType.SQUARE)) {
            for (f0 in doubleArrayOf(440.7, 1000.3, 2217.3, 4001.1)) {
                val blep = render(type, f0, seconds = n.toDouble() / sr)
                val naive = DoubleArray(n)
                var phase = 0.0
                val dt = f0 / sr
                for (i in 0 until n) {
                    naive[i] = if (type == OscType.SAW) {
                        var q = phase + 0.5
                        if (q >= 1.0) q -= 1.0
                        2.0 * q - 1.0
                    } else if (phase < 0.5) 1.0 else -1.0
                    phase += dt
                    if (phase >= 1.0) phase -= 1.0
                }
                val aBlep = inharmonicFraction(blep, f0)
                val aNaive = inharmonicFraction(naive, f0)
                val gain = TestDsp.db(aNaive / aBlep)
                println(
                    "alias $type at $f0 Hz: naive ${"%.1f".format(TestDsp.db(aNaive))} dB, " +
                        "PolyBLEP ${"%.1f".format(TestDsp.db(aBlep))} dB (${"%.1f".format(gain)} dB lower)",
                )
                // A 2-point PolyBLEP smooths each edge over two samples, which is worth about 13-16 dB of aliasing.
                assertTrue("$type $f0 Hz alias only ${"%.1f".format(gain)} dB below naive", gain > 10.0)
            }
        }
    }

    @Test
    fun whiteNoiseIsFlatWithinTwoDb() {
        val buf = NoiseBuffers.loop(pink = false, seed = 7L, length = sr)
        val x = DoubleArray(buf.size) { buf[it].toDouble() }
        val seg = 2048
        val spec = TestDsp.welch(x, seg)
        val edges = doubleArrayOf(100.0, 200.0, 400.0, 800.0, 1600.0, 3200.0, 6000.0)
        val levels = DoubleArray(edges.size - 1) { TestDsp.db(TestDsp.bandPower(spec, seg, sr, edges[it], edges[it + 1])) }
        println("white noise band levels (dB per bin, 100 Hz - 6 kHz): " + levels.joinToString { "%.2f".format(it) })
        val mean = levels.average()
        for (l in levels) assertEquals(mean, l, 2.0)
    }

    @Test
    fun pinkNoiseSlopesAboutMinusThreeDbPerOctave() {
        for (len in intArrayOf(sr, 10 * sr)) {
            val buf = NoiseBuffers.loop(pink = true, seed = 7L, length = len)
            val x = DoubleArray(buf.size) { buf[it].toDouble() }
            val seg = 4096
            val spec = TestDsp.welch(x, seg)
            // power per bin in octave bands; pink falls 3 dB per octave
            val edges = doubleArrayOf(100.0, 200.0, 400.0, 800.0, 1600.0, 3200.0, 6400.0)
            val xs = DoubleArray(edges.size - 1) { it.toDouble() + 0.5 }
            val ys = DoubleArray(xs.size) { TestDsp.db(TestDsp.bandPower(spec, seg, sr, edges[it], edges[it + 1])) }
            val slope = TestDsp.slope(xs, ys)
            println("pink noise (${len / sr} s buffer) slope: ${"%.2f".format(slope)} dB/octave; bands: " + ys.joinToString { "%.1f".format(it) })
            assertEquals(-3.0, slope, 1.0)
        }
    }

    @Test
    fun noiseBuffersAreReproducibleAndLoopWithoutASeam() {
        for (pink in listOf(false, true)) {
            val a = NoiseBuffers.loop(pink, 42L, sr)
            val b = NoiseBuffers.loop(pink, 42L, sr)
            val c = NoiseBuffers.loop(pink, 43L, sr)
            assertTrue(a.contentEquals(b))
            assertTrue(!a.contentEquals(c))
            assertTrue(TestDsp.allFinite(a))
            // the step across the loop point is no bigger than the biggest step elsewhere
            var maxStep = 0.0
            for (i in 1 until a.size) maxStep = max(maxStep, abs(a[i] - a[i - 1]).toDouble())
            val seam = abs(a[0] - a[a.size - 1]).toDouble()
            assertTrue("seam step $seam vs max $maxStep", seam <= maxStep)
        }
    }

    @Test
    fun xorShiftIsDeterministicAndUniform() {
        val a = XorShift64(5L)
        val b = XorShift64(5L)
        var sum = 0.0
        val n = 200_000
        for (i in 0 until n) {
            val x = a.nextDouble()
            assertEquals(x, b.nextDouble(), 0.0)
            assertTrue(x >= 0.0 && x < 1.0)
            sum += x
        }
        assertEquals(0.5, sum / n, 0.005)
        assertTrue(XorShift64(0L).nextLong() != 0L)
    }

    @Test
    fun sineIsAccurateAtTheSampleLevel() {
        val x = render(OscType.SINE, 440.0, seconds = 0.1)
        for (i in x.indices) assertEquals(sin(2.0 * PI * 440.0 * i / sr), x[i], 1e-7)
    }
}

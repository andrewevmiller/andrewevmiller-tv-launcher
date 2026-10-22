package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaperTest {
    private val sr = 48_000

    @Test
    fun identityWithoutFoldCrushOrDrive() {
        var x = -1.0
        while (x <= 1.0) {
            assertEquals(x, Shaper.curve(x, 0.0, 0.0, 0.0), 1e-12)
            assertEquals(x, Shaper.curve(x, 0.0, 1e-4, 0.0), 1e-6)
            x += 0.01
        }
    }

    @Test
    fun tableLookupOfAnIdentityCurveIsExact() {
        // A linear curve interpolates exactly, so an identity table must hand back its input.
        val table = Shaper.buildTable(fold = 0.0, drive = 0.0, crush = 0.0)
        assertEquals(Shaper.TABLE_SIZE, table.size)
        assertEquals(-1.0f, table.first(), 0f)
        assertEquals(1.0f, table.last(), 0f)
        val shaper = Oversampler4x(table)
        val n = 4800
        val buf = DoubleArray(n) { 0.5 * sin(2.0 * PI * 440.0 * it / sr) }
        shaper.process(buf, 0, n)
        // 4x FIR pair: whole-sample delay, tiny passband ripple at 440 Hz
        val d = Oversampler4x.LATENCY_SAMPLES
        for (i in 400 until n) {
            assertEquals(0.5 * sin(2.0 * PI * 440.0 * (i - d) / sr), buf[i], 2e-3)
        }
    }

    @Test
    fun curveShape() {
        // fold, then tanh/tanh(k), k = drive * 1.6, no crush
        val x = 0.3
        val k = 8.0 * 1.6
        val folded = sin(3.0 * x * PI / 2.0)
        assertEquals(Math.tanh(k * folded) / Math.tanh(k), Shaper.curve(x, 3.0, 8.0, 0.0), 1e-12)
        // tanh normalisation puts full scale at full scale
        assertEquals(1.0, Shaper.curve(1.0, 0.0, 6.0, 0.0), 1e-12)
        assertEquals(-1.0, Shaper.curve(-1.0, 0.0, 6.0, 0.0), 1e-12)
        // a harder drive pushes mid-scale values towards full scale
        assertTrue(Shaper.curve(0.3, 0.0, 10.0, 0.0) > Shaper.curve(0.3, 0.0, 2.0, 0.0))
        // odd symmetry
        assertEquals(-Shaper.curve(0.37, 2.5, 7.0, 0.0), Shaper.curve(-0.37, 2.5, 7.0, 0.0), 1e-12)
    }

    @Test
    fun crushQuantisesToTheRightNumberOfLevels() {
        for (levels in intArrayOf(8, 12)) {
            val seen = HashSet<Int>()
            var x = -1.0
            while (x <= 1.0) {
                val y = Shaper.curve(x, 0.0, 1e-4, levels.toDouble())
                val steps = y * levels
                assertEquals("on the grid: $y", Math.rint(steps), steps, 1e-9)
                seen += Math.rint(steps).toInt()
                x += 0.0005
            }
            // -levels..+levels inclusive
            assertEquals("levels $levels", 2 * levels + 1, seen.size)
        }
        // crush after drive and fold is still a staircase
        val seen = HashSet<Int>()
        var x = -1.0
        while (x <= 1.0) {
            val y = Shaper.curve(x, 3.5, 10.0, 12.0)
            assertEquals(Math.rint(y * 12), y * 12, 1e-9)
            seen += Math.rint(y * 12).toInt()
            x += 0.001
        }
        assertTrue(seen.size <= 25)
        assertTrue(seen.size > 8)
    }

    @Test
    fun outputIsBoundedByOne() {
        for (fold in doubleArrayOf(0.0, 1.2, 2.2, 3.5)) {
            for (drive in doubleArrayOf(0.5, 4.0, 10.0)) {
                for (crush in doubleArrayOf(0.0, 8.0, 12.0)) {
                    var x = -1.0
                    while (x <= 1.0) {
                        assertTrue(abs(Shaper.curve(x, fold, drive, crush)) <= 1.0 + 1e-12)
                        x += 0.003
                    }
                    for (v in Shaper.buildTable(fold, drive, crush)) assertTrue(abs(v) <= 1.0f)
                }
            }
        }
    }

    @Test
    fun oversampledOutputStaysNearFullScaleAndFinite() {
        // Loud input beyond +-1 is clamped by the table; the down-sampling FIR may overshoot a little (Gibbs).
        val table = Shaper.buildTable(3.0, 8.0, 0.0)
        val shaper = Oversampler4x(table)
        val n = 48000
        val buf = DoubleArray(n) { 2.5 * sin(2.0 * PI * 1320.0 * it / sr) }
        shaper.process(buf, 0, n)
        assertTrue(TestDsp.allFinite(buf))
        var peak = 0.0
        for (v in buf) peak = maxOf(peak, abs(v))
        println("oversampled shaper peak on a hard-driven 1320 Hz input: ${"%.3f".format(peak)}")
        var worst = 0.0
        for (h in Oversampler4x.H) worst += abs(h)
        assertTrue(peak <= worst)
        assertTrue(peak < 1.25)
    }

    @Test
    fun oversamplerFirIsAUnityGainLowPassAtTheOriginalNyquist() {
        val h = Oversampler4x.H
        assertEquals(Oversampler4x.TAPS, h.size)
        assertEquals(1.0, h.sum(), 1e-12)
        for (i in h.indices) assertEquals(h[i], h[h.size - 1 - i], 1e-15) // linear phase
        // |H| of up then down is H^2 at the 4x rate; f in Hz at 192 kHz
        fun mag(f: Double): Double {
            var re = 0.0
            var im = 0.0
            for (i in h.indices) {
                val w = 2.0 * PI * f / (4.0 * sr) * i
                re += h[i] * cos(w)
                im -= h[i] * sin(w)
            }
            return sqrt(re * re + im * im)
        }
        val line = StringBuilder("oversampler FIR response up*down (dB):")
        for (f in doubleArrayOf(1000.0, 5000.0, 10000.0, 15000.0, 20000.0, 24000.0, 30000.0, 40000.0, 60000.0)) {
            line.append("  ${f.toInt()} Hz ${"%.1f".format(40.0 * log10(mag(f)))}")
        }
        println(line)
        assertEquals(0.0, 40.0 * log10(mag(5000.0)), 0.3)
        assertTrue(40.0 * log10(mag(10000.0)) > -1.5)
        assertTrue(40.0 * log10(mag(60000.0)) < -60.0) // image rejection well away from the band
    }

    @Test
    fun oversamplingCutsAliasingOfTheHarmonicsTheFolderMakes() {
        val f0 = 3217.0
        val n = 65536
        val table = Shaper.buildTable(3.5, 8.0, 0.0)
        val x = DoubleArray(n) { 0.9 * sin(2.0 * PI * f0 * it / sr) }
        // 1x: read the same table directly, no oversampling
        val direct = DoubleArray(n)
        for (i in 0 until n) {
            val scaled = (x[i].coerceIn(-1.0, 1.0) + 1.0) * (Shaper.TABLE_SIZE - 1) * 0.5
            val j = minOf(scaled.toInt(), Shaper.TABLE_SIZE - 2)
            direct[i] = table[j] + (table[j + 1] - table[j]) * (scaled - j)
        }
        val over = x.copyOf()
        Oversampler4x(table).process(over, 0, n)
        val seg = 8192
        fun inharmonic(buf: DoubleArray): Double {
            val spec = TestDsp.welch(buf, seg)
            val binHz = sr.toDouble() / seg
            var total = 0.0
            var other = 0.0
            for (k in 3 until spec.size) {
                total += spec[k]
                val hNum = k * binHz / f0
                val nearest = Math.round(hNum).toInt()
                if (nearest < 1 || nearest > (sr / 2 / f0).toInt() || abs(hNum - nearest) * f0 / binHz >= 6.0) other += spec[k]
            }
            return other / total
        }
        val a1 = inharmonic(direct)
        val a4 = inharmonic(over)
        println("folder aliasing at $f0 Hz: 1x ${"%.1f".format(TestDsp.db(a1))} dB, 4x ${"%.1f".format(TestDsp.db(a4))} dB")
        assertTrue("4x only ${"%.1f".format(TestDsp.db(a1 / a4))} dB better", TestDsp.db(a1 / a4) > 10.0)
    }

    @Test
    fun resetClearsTheHistory() {
        val table = Shaper.buildTable(2.2, 6.0, 0.0)
        val s = Oversampler4x(table)
        val a = DoubleArray(500) { sin(it * 0.3) }
        s.process(a, 0, a.size)
        s.reset()
        val b = DoubleArray(500) { sin(it * 0.3) }
        s.process(b, 0, b.size)
        val fresh = Oversampler4x(table)
        val c = DoubleArray(500) { sin(it * 0.3) }
        fresh.process(c, 0, c.size)
        assertTrue(b.contentEquals(c))
    }
}

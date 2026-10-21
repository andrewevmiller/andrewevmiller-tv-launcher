package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The sound's character: a sine wavefolder, a tanh soft clipper, and an optional bit crusher.
 *
 * Like the mockup (a Web Audio WaveShaperNode) the curve is baked into a 4096-point table over
 * x in [-1, 1] and read with linear interpolation, and the input is clamped to [-1, 1].
 */
internal object Shaper {
    /** Global push into the saturator on top of each sound's own drive. */
    const val DRIVE_MULT = 1.6
    const val TABLE_SIZE = 4096

    /** The exact curve (no table): fold, then tanh with k = drive * 1.6, then quantise to `crush` steps. */
    fun curve(x: Double, fold: Double, drive: Double, crush: Double): Double {
        val k = drive * DRIVE_MULT
        var y = if (fold > 0.0) sin(fold * x * PI / 2.0) else x
        if (k > 1e-9) y = tanh(k * y) / tanh(k)
        // JS Math.round rounds halves up; floor(v + 0.5) does the same.
        if (crush > 0.0) y = floor(y * crush + 0.5) / crush
        return y
    }

    fun buildTable(fold: Double, drive: Double, crush: Double): FloatArray {
        val t = FloatArray(TABLE_SIZE)
        for (i in 0 until TABLE_SIZE) {
            val x = i.toDouble() / (TABLE_SIZE - 1) * 2.0 - 1.0
            t[i] = curve(x, fold, drive, crush).toFloat()
        }
        return t
    }
}

/**
 * Runs a [Shaper] table at 4x the sample rate: zero-stuff and low-pass up (polyphase), shape, low-pass
 * and decimate. Both FIRs are the same windowed-sinc (Kaiser) with the cutoff at the original Nyquist.
 * One instance per voice; the history is zero at construction and after [reset].
 */
internal class Oversampler4x(private val table: FloatArray) {
    // Up filter, polyphase: phase p uses taps 4k + p, k = 0 until PHASE_TAPS, history newest first.
    private val upHist = DoubleArray(PHASE_TAPS * 2)
    private var upPos = 0
    private val downHist = DoubleArray(TAPS * 2)
    private var downPos = 0

    fun reset() {
        upHist.fill(0.0)
        downHist.fill(0.0)
        upPos = 0
        downPos = 0
    }

    /** Shapes `buf[from until to]` in place. Output is delayed by [LATENCY_SAMPLES]. */
    fun process(buf: DoubleArray, from: Int, to: Int) {
        val t = table
        val half = (Shaper.TABLE_SIZE - 1) * 0.5
        val lastCell = Shaper.TABLE_SIZE - 2
        val up = UP
        val down = H
        var uPos = upPos
        var dPos = downPos
        for (n in from until to) {
            uPos = if (uPos == 0) PHASE_TAPS - 1 else uPos - 1
            val x = buf[n]
            upHist[uPos] = x
            upHist[uPos + PHASE_TAPS] = x
            for (p in 0 until FACTOR) {
                var acc = 0.0
                val base = p * PHASE_TAPS
                for (k in 0 until PHASE_TAPS) acc += up[base + k] * upHist[uPos + k]
                // WaveShaper: clamp to the curve ends, then interpolate between table points.
                val v = if (acc < -1.0) -1.0 else if (acc > 1.0) 1.0 else acc
                val scaled = (v + 1.0) * half
                var i = scaled.toInt()
                if (i > lastCell) i = lastCell
                val frac = scaled - i
                val y0 = t[i].toDouble()
                val s = y0 + (t[i + 1] - y0) * frac
                dPos = if (dPos == 0) TAPS - 1 else dPos - 1
                downHist[dPos] = s
                downHist[dPos + TAPS] = s
            }
            var out = 0.0
            for (i in 0 until TAPS) out += down[i] * downHist[dPos + i]
            buf[n] = out
        }
        upPos = uPos
        downPos = dPos
    }

    companion object {
        const val FACTOR = 4
        const val TAPS = 32
        private const val PHASE_TAPS = TAPS / FACTOR

        /**
         * Delay of the whole stage in base-rate samples. Both FIRs delay by (TAPS - 1) / 2 samples at 4x and the
         * decimator reads the last phase of each group, so the total is (TAPS - 1 - (FACTOR - 1)) / FACTOR = 7: whole samples.
         */
        const val LATENCY_SAMPLES = (TAPS - FACTOR) / FACTOR

        private const val CUTOFF = 0.5 / FACTOR // original Nyquist, as a fraction of the 4x rate
        private const val KAISER_BETA = 6.0

        /** Low-pass at the original Nyquist, unity gain at DC. */
        val H: DoubleArray = design()

        /** [H] split by phase and scaled by 4 to make up for the zero-stuffing: index p * PHASE_TAPS + k. */
        private val UP: DoubleArray = DoubleArray(TAPS).also { u ->
            for (p in 0 until FACTOR) for (k in 0 until PHASE_TAPS) u[p * PHASE_TAPS + k] = FACTOR * H[FACTOR * k + p]
        }

        private fun design(): DoubleArray {
            val h = DoubleArray(TAPS)
            val mid = (TAPS - 1) / 2.0
            val i0Beta = bessel0(KAISER_BETA)
            var sum = 0.0
            for (i in 0 until TAPS) {
                val t = i - mid
                val sinc = if (t == 0.0) 2.0 * CUTOFF else sin(2.0 * PI * CUTOFF * t) / (PI * t)
                val r = t / mid
                val w = bessel0(KAISER_BETA * sqrt((1.0 - r * r).coerceAtLeast(0.0))) / i0Beta
                h[i] = sinc * w
                sum += h[i]
            }
            for (i in 0 until TAPS) h[i] /= sum
            return h
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var k = 1
            while (k < 60) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < sum * 1e-17) break
                k++
            }
            return sum
        }
    }
}

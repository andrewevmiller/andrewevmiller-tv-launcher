package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * RBJ biquad, transposed direct form II in doubles, with the same Q conventions as Web Audio:
 * low-pass and high-pass take Q in dB (`alpha = sin(w0) / (2 * 10^(Q/20))`), band-pass takes a linear Q
 * (`alpha = sin(w0) / (2 Q)`, constant 0 dB peak). Mockup `q` values (1.5, 3, 7...) are dB for the low-pass.
 */
internal class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun setLowpass(freq: Double, qDb: Double, sampleRate: Int) {
        val w0 = omega(freq, sampleRate)
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * 10.0.pow(qDb / 20.0))
        val a0 = 1.0 + alpha
        b0 = (1.0 - c) * 0.5 / a0
        b1 = (1.0 - c) / a0
        b2 = b0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setHighpass(freq: Double, qDb: Double, sampleRate: Int) {
        val w0 = omega(freq, sampleRate)
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * 10.0.pow(qDb / 20.0))
        val a0 = 1.0 + alpha
        b0 = (1.0 + c) * 0.5 / a0
        b1 = -(1.0 + c) / a0
        b2 = b0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setBandpass(freq: Double, qLinear: Double, sampleRate: Int) {
        val w0 = omega(freq, sampleRate)
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * qLinear)
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    /** Filters `buf[from until to]` in place with fixed coefficients (call a `set...` first). */
    fun processBlock(buf: DoubleArray, from: Int, to: Int) {
        var s1 = z1
        var s2 = z2
        val c0 = b0
        val c1 = b1
        val c2 = b2
        val d1 = a1
        val d2 = a2
        for (n in from until to) {
            val x = buf[n]
            val y = c0 * x + s1
            s1 = c1 * x - d1 * y + s2
            s2 = c2 * x - d2 * y
            buf[n] = y
        }
        z1 = s1
        z2 = s2
    }

    /**
     * Filters `buf[from until to]` in place while the centre/cutoff frequency sweeps exponentially
     * `fFrom -> fTo` over [sweepSamples] (sample index 0 = start of the sweep) and then holds.
     * Coefficients are refreshed every [BLOCK] samples. [mode] picks low-pass (q in dB) or
     * band-pass (q linear). The filter state is not reset first, so call [reset] for a new voice.
     */
    fun sweep(
        mode: Mode,
        buf: DoubleArray,
        from: Int,
        to: Int,
        fFrom: Double,
        fTo: Double,
        sweepSamples: Double,
        q: Double,
        sampleRate: Int,
    ) {
        val f0 = fFrom.coerceAtLeast(1.0)
        val f1 = fTo.coerceAtLeast(1.0)
        val lnRatio = ln(f1 / f0)
        var n = from
        while (n < to) {
            val u = if (sweepSamples > 0.0) min(n / sweepSamples, 1.0) else 1.0
            val f = f0 * exp(lnRatio * u)
            if (mode == Mode.LOWPASS_DB) setLowpass(f, q, sampleRate) else setBandpass(f, q, sampleRate)
            processBlock(buf, n, min(n + BLOCK, to))
            n += BLOCK
        }
    }

    enum class Mode { LOWPASS_DB, BANDPASS_LINEAR }

    companion object {
        const val BLOCK = 16

        /** Largest cutoff the coefficient maths is asked for, as a fraction of the sample rate. */
        private const val MAX_FRACTION = 0.45

        private fun omega(freq: Double, sampleRate: Int): Double {
            val f = freq.coerceIn(1.0, sampleRate * MAX_FRACTION)
            return 2.0 * PI * f / sampleRate
        }
    }
}

package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** The four tonal shapes. Supersaw is [SAW] x 7 and the noise voices have no oscillator at all. */
internal enum class OscType { SINE, TRIANGLE, SAW, SQUARE }

/**
 * Phase-accumulating oscillators that mix straight into a buffer, like Web Audio's OscillatorNode:
 * phase 0 at the start (sine, triangle and saw start at 0 and rise; square starts at +1), amplitude
 * +-1, an exponential frequency glide, and a static detune in cents on top of it.
 *
 * Saw and square are band-limited with PolyBLEP. Triangle and sine need nothing: the triangle's
 * harmonics fall as 1/n^2.
 */
internal object Oscillators {
    private const val TWO_PI = 2.0 * PI

    /** Two-sample polynomial band-limited step residual; [t] is phase in [0,1), [dt] the phase increment. */
    fun polyBlep(t: Double, dt: Double): Double {
        if (t < dt) {
            val x = t / dt
            return x + x - x * x - 1.0
        }
        if (t > 1.0 - dt) {
            val x = (t - 1.0) / dt
            return x * x + x + x + 1.0
        }
        return 0.0
    }

    /**
     * Adds `scale * wave` into `out[from until to]`.
     *
     * Sample index `n` is time `n / sampleRate` from the start of the note, so `from` is where this
     * voice starts (its jitter) and the glide stays locked to the note, as in the mockup. The
     * frequency runs `freq -> glideTo` exponentially over [glideSamples] samples and holds at `glideTo`
     * afterwards. Pass `Double.NaN` (or `glideSamples <= 0`) for no glide.
     */
    fun add(
        type: OscType,
        out: DoubleArray,
        from: Int,
        to: Int,
        freq: Double,
        glideTo: Double,
        glideSamples: Double,
        cents: Double,
        sampleRate: Int,
        scale: Double,
    ) {
        if (to <= from) return
        val detuneRatio = if (cents == 0.0) 1.0 else 2.0.pow(cents / 1200.0)
        val invSr = 1.0 / sampleRate
        when (type) {
            OscType.SINE ->
                loop(out, from, to, freq, glideTo, glideSamples, detuneRatio, invSr, scale) { p, _ ->
                    sin(TWO_PI * p)
                }
            OscType.TRIANGLE ->
                loop(out, from, to, freq, glideTo, glideSamples, detuneRatio, invSr, scale) { p, _ ->
                    var q = p + 0.25
                    if (q >= 1.0) q -= 1.0
                    1.0 - 4.0 * abs(q - 0.5)
                }
            OscType.SAW ->
                loop(out, from, to, freq, glideTo, glideSamples, detuneRatio, invSr, scale) { p, dt ->
                    var q = p + 0.5
                    if (q >= 1.0) q -= 1.0
                    2.0 * q - 1.0 - polyBlep(q, dt)
                }
            OscType.SQUARE ->
                loop(out, from, to, freq, glideTo, glideSamples, detuneRatio, invSr, scale) { p, dt ->
                    var q = p + 0.5
                    if (q >= 1.0) q -= 1.0
                    (if (p < 0.5) 1.0 else -1.0) + polyBlep(p, dt) - polyBlep(q, dt)
                }
        }
    }

    private inline fun loop(
        out: DoubleArray,
        from: Int,
        to: Int,
        freq: Double,
        glideTo: Double,
        glideSamples: Double,
        detuneRatio: Double,
        invSr: Double,
        scale: Double,
        wave: (Double, Double) -> Double,
    ) {
        val glides = !glideTo.isNaN() && glideSamples > 0.0 && glideTo != freq
        val step = if (glides) (glideTo / freq).pow(1.0 / glideSamples) else 1.0
        var f = when {
            !glides -> freq
            from >= glideSamples -> glideTo
            else -> freq * (glideTo / freq).pow(from / glideSamples)
        }
        var phase = 0.0
        var n = from
        while (n < to) {
            val dt = f * detuneRatio * invSr
            out[n] += scale * wave(phase, dt)
            phase += dt
            if (phase >= 1.0) phase -= 1.0
            n++
            if (glides) f = if (n < glideSamples) f * step else glideTo
        }
    }
}

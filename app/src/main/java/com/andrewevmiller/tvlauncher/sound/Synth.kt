package com.andrewevmiller.tvlauncher.sound

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class Waveform { SINE, TRIANGLE, SAW, SQUARE, SUPERSAW }

/** One note: detuned oscillators -> wavefolder -> tanh -> resonant low-pass sweep, plus an optional noise click. */
internal class Voice(
    val f: Double, val at: Double, val dur: Double, val gain: Double,
    val voices: Int = 2, val detune: Double = 9.0, val glide: Double? = null,
    val fold: Double = 2.2, val drive: Double = 6.0, val crush: Int = 0,
    val lpFrom: Double = 7000.0, val lpTo: Double = 2500.0, val q: Double = 1.5, val click: Double = 0.0,
)

internal class Biquad {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    fun process(x: Double, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/**
 * The sound renderer, ported from the mockup's `sfx` block. Free of Android types so it runs as plain
 * JVM code in tests. [random] supplies every random draw (initial oscillator phases, the click noise) in
 * a fixed order, so a seeded source gives repeatable audio; production passes `Math.random()`.
 */
internal object Synth {
    const val RATE = 48000

    /** Lifts the mockup's very quiet levels (about -30 dBFS) to something audible on a TV speaker. */
    const val OUTPUT_BOOST = 8.0

    /**
     * How many samples share one set of low-pass coefficients (4 samples is 0.08 ms). The cutoff sweep is
     * smooth, so refreshing it a quarter as often saves most of the pow, sin and cos calls per sample.
     * Against refreshing every sample the output differs by at most 13 of 32000 (-68 dB) and 0.25% RMS;
     * 16 was cheaper still but four times as far off, and not worth the 0.4 ms. Tests pass 1 to compare
     * with the original per-sample renderer, which it matches exactly at 16-bit output.
     */
    const val CONTROL_PERIOD = 4

    private const val ATTACK_S = 0.003
    private const val CLICK_S = 0.02
    private const val CLICK_DECAY_S = 0.012
    private const val ENV_FLOOR = 1e-4

    fun render(
        voices: List<Voice>,
        totalSeconds: Double,
        waveform: Waveform,
        random: () -> Double,
        controlPeriod: Int = CONTROL_PERIOD,
    ): ShortArray {
        val n = (totalSeconds * RATE).toInt()
        val mix = DoubleArray(n)
        for (v in voices) renderVoice(v, mix, waveform, random, controlPeriod)
        return master(mix)
    }

    private fun osc(waveform: Waveform, p: Double): Double = when (waveform) {
        Waveform.SINE -> sin(2 * PI * p)
        Waveform.TRIANGLE -> 4 * abs(p - 0.5) - 1
        Waveform.SQUARE -> if (p < 0.5) 1.0 else -1.0
        else -> 2 * p - 1
    }

    /** Number of samples i with i / RATE < [seconds], computed the way the per-sample code compared them. */
    private fun samplesBefore(seconds: Double): Int {
        var i = 0
        while (i.toDouble() / RATE < seconds) i++
        return i
    }

    private fun renderVoice(v: Voice, mix: DoubleArray, waveform: Waveform, random: () -> Double, controlPeriod: Int) {
        val start = (v.at * RATE).toInt()
        val len = minOf((v.dur * RATE).toInt(), mix.size - start)
        val count = if (waveform == Waveform.SUPERSAW) 7 else v.voices
        val spread = if (count == 1) doubleArrayOf(0.0) else DoubleArray(count) { -1 + 2.0 * it / (count - 1) }
        val detune = if (waveform == Waveform.SUPERSAW) maxOf(v.detune, 22.0) else v.detune
        // Draw the starting phases first and always, so the random order never depends on a voice's length.
        val phases = DoubleArray(count) { random() }
        if (len <= 0) return

        // Everything that does not change from sample to sample is worked out once, here.
        val detuneRatio = DoubleArray(count) { 2.0.pow(spread[it] * detune / 1200) }
        val glideStep = if (v.glide != null) (v.glide / v.f).pow(1.0 / (v.dur * RATE)) else 1.0
        var base = v.f
        val k = v.drive * 1.6
        val norm = tanh(k)
        val oneOverRootCount = 1.0 / sqrt(count.toDouble())
        val fold = v.fold
        val foldScale = fold * PI / 2
        val crush = v.crush
        val qLin = 10.0.pow(v.q / 20)
        val sweep = max(60.0, v.lpTo) / v.lpFrom
        val cutoffLimit = RATE * 0.45

        val peak = v.gain
        val attackN = samplesBefore(ATTACK_S)
        val attackStep = (peak / ENV_FLOOR).pow(1.0 / (ATTACK_S * RATE))
        val decayStep = (ENV_FLOOR / peak).pow(1.0 / (max(1e-3, v.dur - ATTACK_S) * RATE))
        var env = ENV_FLOOR

        val clickN = if (v.click > 0) samplesBefore(CLICK_S) else 0
        val clickDecayN = samplesBefore(CLICK_DECAY_S)
        val clickStep = if (v.click > 0) (ENV_FLOOR / v.click).pow(1.0 / (CLICK_DECAY_S * RATE)) else 1.0
        var clickGain = v.click
        // noise burst through a 3.2kHz band-pass (Q 1.2)
        val wb = 2 * PI * 3200 / RATE
        val ab = sin(wb) / (2 * 1.2)
        val a0b = 1 + ab
        val cb0 = ab / a0b; val cb2 = -ab / a0b; val ca1 = -2 * cos(wb) / a0b; val ca2 = (1 - ab) / a0b

        val lp = Biquad()
        val bp = Biquad()
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        for (i in 0 until len) {
            // resonant low-pass with an exponential cutoff sweep (RBJ biquad); coefficients refreshed per block
            if (i % controlPeriod == 0) {
                val tMid = (i + controlPeriod / 2).toDouble() / RATE
                val fc = (v.lpFrom * sweep.pow(tMid / v.dur)).coerceAtMost(cutoffLimit)
                val w = 2 * PI * fc / RATE
                val alpha = sin(w) / (2 * qLin)
                val c = cos(w)
                val a0 = 1 + alpha
                b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
            }

            var sum = 0.0
            for (o in 0 until count) {
                var p = phases[o] + base * detuneRatio[o] / RATE
                if (p >= 1.0) p -= 1.0
                phases[o] = p
                sum += osc(waveform, p)
            }
            base *= glideStep
            sum *= oneOverRootCount
            var y = sum.coerceIn(-1.0, 1.0)
            if (fold > 0) y = sin(foldScale * y)
            y = tanh(k * y) / norm
            if (crush > 0) y = (y * crush).roundToInt().toDouble() / crush
            y = lp.process(y, b0, b1, b2, a1, a2)

            var out = y * env
            env *= if (i + 1 <= attackN) attackStep else decayStep
            if (i < clickN) {
                val noise = bp.process(random() * 2 - 1, cb0, 0.0, cb2, ca1, ca2)
                out += noise * clickGain
                if (i + 1 <= clickDecayN) clickGain *= clickStep
            }
            mix[start + i] += out
        }
    }

    /** 70Hz high-pass, output trim and a soft limiter. */
    private fun master(mix: DoubleArray): ShortArray {
        val hp = Biquad()
        val wh = 2 * PI * 70 / RATE
        val ah = sin(wh) / (2 * 0.707)
        val a0h = 1 + ah
        val ch = cos(wh)
        val h0 = (1 + ch) / 2 / a0h; val h1 = -(1 + ch) / a0h; val h2 = h0; val g1 = -2 * ch / a0h; val g2 = (1 - ah) / a0h
        val pcm = ShortArray(mix.size)
        for (i in mix.indices) {
            val y = hp.process(mix[i], h0, h1, h2, g1, g2)
            pcm[i] = (tanh(y * 0.8 * OUTPUT_BOOST) * 32000).toInt().toShort()
        }
        return pcm
    }

    fun wav(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        return ByteArrayOutputStream().apply { write(h.array()); write(data) }.toByteArray()
    }
}

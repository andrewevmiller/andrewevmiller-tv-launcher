package com.andrewevmiller.tvlauncher.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The renderer exactly as it was before the performance pass (FieldSound.render at commit 9f5b843 and
 * since), kept only to compare the optimized [Synth] against. The sole change is that random draws come
 * from [random] instead of Math.random(), so both can be run on the same seed. Do not "improve" this.
 */
internal object ReferenceSynth {
    private const val rate = 48000
    private const val OUTPUT_BOOST = 8.0

    private fun osc(waveform: Waveform, p: Double): Double = when (waveform) {
        Waveform.SINE -> sin(2 * PI * p)
        Waveform.TRIANGLE -> 4 * abs(p - 0.5) - 1
        Waveform.SQUARE -> if (p < 0.5) 1.0 else -1.0
        else -> 2 * p - 1
    }

    fun render(voices: List<Voice>, totalSeconds: Double, waveform: Waveform, random: () -> Double): ShortArray {
        val n = (totalSeconds * rate).toInt()
        val mix = DoubleArray(n)
        for (v in voices) {
            val start = (v.at * rate).toInt()
            val len = minOf((v.dur * rate).toInt(), n - start)
            val count = if (waveform == Waveform.SUPERSAW) 7 else v.voices
            val spread = if (count == 1) doubleArrayOf(0.0) else DoubleArray(count) { -1 + 2.0 * it / (count - 1) }
            val detune = if (waveform == Waveform.SUPERSAW) maxOf(v.detune, 22.0) else v.detune
            val phases = DoubleArray(count) { random() }
            val k = v.drive * 1.6
            val norm = tanh(k)
            val lp = Biquad()
            val bp = Biquad()
            val peak = v.gain
            for (i in 0 until len) {
                val t = i.toDouble() / rate
                val base = if (v.glide != null) v.f * (v.glide / v.f).pow(t / v.dur) else v.f
                var sum = 0.0
                for (o in 0 until count) {
                    val fo = base * 2.0.pow(spread[o] * detune / 1200)
                    phases[o] = (phases[o] + fo / rate) % 1.0
                    sum += osc(waveform, phases[o])
                }
                sum /= sqrt(count.toDouble())
                var y = sum.coerceIn(-1.0, 1.0)
                if (v.fold > 0) y = sin(v.fold * y * PI / 2)
                y = tanh(k * y) / norm
                if (v.crush > 0) y = (y * v.crush).roundToInt().toDouble() / v.crush
                // resonant low-pass with an exponential cutoff sweep (RBJ biquad)
                val fc = (v.lpFrom * (maxOf(60.0, v.lpTo) / v.lpFrom).pow(t / v.dur)).coerceAtMost(rate * 0.45)
                val qLin = 10.0.pow(v.q / 20)
                val w = 2 * PI * fc / rate
                val alpha = sin(w) / (2 * qLin)
                val c = cos(w)
                val a0 = 1 + alpha
                y = lp.process(y, (1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
                val env = if (t < 0.003) 1e-4 * (peak / 1e-4).pow(t / 0.003)
                else peak * (1e-4 / peak).pow((t - 0.003) / maxOf(1e-3, v.dur - 0.003))
                var out = y * env
                if (v.click > 0 && t < 0.02) {
                    // noise burst through a 3.2kHz band-pass (Q 1.2), 12ms decay
                    val wb = 2 * PI * 3200 / rate
                    val ab = sin(wb) / (2 * 1.2)
                    val a0b = 1 + ab
                    val noise = bp.process(random() * 2 - 1, ab / a0b, 0.0, -ab / a0b, -2 * cos(wb) / a0b, (1 - ab) / a0b)
                    out += noise * v.click * (1e-4 / v.click).pow(minOf(t, 0.012) / 0.012)
                }
                mix[start + i] += out
            }
        }
        val hp = Biquad()
        val wh = 2 * PI * 70 / rate
        val ah = sin(wh) / (2 * 0.707)
        val a0h = 1 + ah
        val ch = cos(wh)
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val y = hp.process(mix[i], (1 + ch) / 2 / a0h, -(1 + ch) / a0h, (1 + ch) / 2 / a0h, -2 * ch / a0h, (1 - ah) / a0h)
            pcm[i] = (tanh(y * 0.8 * OUTPUT_BOOST) * 32000).toInt().toShort()
        }
        return pcm
    }
}

package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small seedable PRNG (xorshift64*). Renders must be reproducible for a given seed, so nothing in the
 * synth uses kotlin.random or Math.random.
 */
internal class XorShift64(seed: Long) {
    private var s: Long = splitMix(seed).let { if (it == 0L) 0x2545F4914F6CDD1DL else it }

    fun nextLong(): Long {
        var x = s
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        s = x
        return x * 0x2545F4914F6CDD1DL
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / 9007199254740992.0)

    /** Uniform in [-1, 1), like `Math.random() * 2 - 1` in the mockup. */
    fun nextSigned(): Double = nextDouble() * 2.0 - 1.0

    companion object {
        /** SplitMix64 finalizer: spreads small or similar seeds into well-mixed state. */
        fun splitMix(z0: Long): Long {
            var z = z0 + -7046029254386353131L // 0x9E3779B97F4A7C15
            z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
    }
}

/**
 * The noise sources: loopable white and pink buffers for the noise waveforms, and the short white
 * burst behind the attack click.
 *
 * White and pink come from the same random sequence, exactly as in the mockup, with pink made by
 * Paul Kellet's 7-pole filter (x 0.11). The mockup loops a raw 1 s buffer and accepts a seam; here the
 * pink filter is warmed up before recording and the loop seam is cross-faded (equal power) so a
 * voice longer than the remaining loop (the 1.7 s sleep sound) does not click.
 */
internal object NoiseBuffers {
    private const val WARMUP_SAMPLES = 4800
    private const val SEAM_SAMPLES = 2400

    /** A [length]-sample buffer that loops without a seam. Same seed gives the same buffer. */
    fun loop(pink: Boolean, seed: Long, length: Int): FloatArray {
        require(length > SEAM_SAMPLES) { "loop buffer too short" }
        val total = WARMUP_SAMPLES + length + SEAM_SAMPLES
        val raw = FloatArray(total)
        val rng = XorShift64(seed)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        for (i in 0 until total) {
            val x = rng.nextSigned()
            if (pink) {
                b0 = 0.99886 * b0 + x * 0.0555179
                b1 = 0.99332 * b1 + x * 0.0750759
                b2 = 0.969 * b2 + x * 0.153852
                b3 = 0.8665 * b3 + x * 0.3104856
                b4 = 0.55 * b4 + x * 0.5329522
                b5 = -0.7616 * b5 - x * 0.016898
                raw[i] = ((b0 + b1 + b2 + b3 + b4 + b5 + b6 + x * 0.5362) * 0.11).toFloat()
                b6 = x * 0.115926
            } else {
                raw[i] = x.toFloat()
            }
        }
        val out = FloatArray(length)
        // out[i] for i < SEAM fades from the continuation of the tail into the head.
        for (i in 0 until length) {
            val head = raw[WARMUP_SAMPLES + i]
            if (i < SEAM_SAMPLES) {
                val tail = raw[WARMUP_SAMPLES + length + i]
                val theta = 0.5 * PI * (i + 0.5) / SEAM_SAMPLES
                out[i] = (tail * cos(theta) + head * sin(theta)).toFloat()
            } else {
                out[i] = head
            }
        }
        return out
    }

    /** Plain white burst, [-1, 1) uniform, used for the attack click. */
    fun burst(seed: Long, length: Int): FloatArray {
        val rng = XorShift64(seed)
        return FloatArray(length) { rng.nextSigned().toFloat() }
    }
}

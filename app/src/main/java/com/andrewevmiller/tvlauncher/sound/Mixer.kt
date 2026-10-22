package com.andrewevmiller.tvlauncher.sound

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/** A rendered one-shot: mono float samples at the synth's sample rate (48 kHz). Treat [samples] as read-only. */
class Clip(val samples: FloatArray) {
    val size: Int get() = samples.size
}

/** Playback rate for a pitch shift of [cents] (100 cents = a semitone): `2^(cents/1200)`. */
fun rateForCents(cents: Double): Double = 2.0.pow(cents / 1200.0)

/**
 * Soft limiter used at the mixer output: untouched up to [LIMITER_KNEE], then a tanh bend that approaches but
 * never exceeds full scale, so overlapping sounds cannot clip. NaN becomes silence.
 */
fun softLimit(x: Float): Float {
    if (x != x) return 0f
    val a = abs(x)
    if (a <= LIMITER_KNEE) return x
    val room = 1f - LIMITER_KNEE
    val y = LIMITER_KNEE + room * tanh((a - LIMITER_KNEE) / room)
    return if (x < 0f) -y else y
}

const val LIMITER_KNEE = 0.8f

/**
 * Sums overlapping one-shot [Clip]s into audio blocks. Pure Kotlin so the audio-thread logic is unit-tested on
 * the JVM; the Android engine only moves the blocks into an AudioTrack.
 *
 * - [add] (any thread) starts a voice: a clip, a playback [rate] (linear-interpolated resampling, so +-5 cents is
 *   `rateForCents(+-5.0)`), a [gain], and a delay in output samples (sample-accurate, also across block
 *   boundaries). With all [maxVoices] busy the oldest voice is stolen.
 * - [mix] (the audio thread) overwrites a block with the sum of all voices, times [masterGain], through
 *   [softLimit].
 *
 * No allocation in [add] or [mix]: the voice table is preallocated struct-of-arrays. One short lock guards the
 * table; both calls hold it for microseconds.
 */
class Mixer(val maxVoices: Int = 16) {
    private val lock = Any()
    private val clips = arrayOfNulls<FloatArray>(maxVoices)
    private val pos = DoubleArray(maxVoices)
    private val rate = DoubleArray(maxVoices)
    private val gain = FloatArray(maxVoices)
    private val delay = IntArray(maxVoices)
    private val started = LongArray(maxVoices)
    private var counter = 0L
    private var active = 0

    /** Linear gain applied to the sum, before the limiter. */
    @Volatile
    var masterGain: Float = 1f

    fun add(clip: Clip, rate: Double = 1.0, gain: Float = 1f, delaySamples: Int = 0) {
        val data = clip.samples
        if (data.isEmpty()) return
        val r = if (rate > 0.0 && rate.isFinite()) rate else 1.0
        val g = if (gain.isNaN()) 0f else gain
        synchronized(lock) {
            var slot = -1
            for (i in 0 until maxVoices) {
                if (clips[i] == null) {
                    slot = i
                    break
                }
            }
            if (slot < 0) { // steal the oldest voice
                slot = 0
                for (i in 1 until maxVoices) if (started[i] < started[slot]) slot = i
                active--
            }
            clips[slot] = data
            pos[slot] = 0.0
            this.rate[slot] = r
            this.gain[slot] = g
            delay[slot] = if (delaySamples > 0) delaySamples else 0
            started[slot] = counter++
            active++
        }
    }

    /** True while any voice is playing or waiting out its delay. */
    fun hasActiveVoices(): Boolean = synchronized(lock) { active > 0 }

    /** Number of voices playing or waiting out their delay. */
    val activeVoices: Int get() = synchronized(lock) { active }

    /** Drops every voice immediately. */
    fun clear() {
        synchronized(lock) {
            for (i in 0 until maxVoices) clips[i] = null
            active = 0
        }
    }

    /** Overwrites `out[0 until count]` with the next [count] samples of the mix. */
    fun mix(out: FloatArray, count: Int = out.size) {
        java.util.Arrays.fill(out, 0, count, 0f)
        synchronized(lock) {
            if (active == 0) return
            for (v in 0 until maxVoices) {
                val data = clips[v] ?: continue
                val wait = delay[v]
                if (wait >= count) {
                    delay[v] = wait - count
                    continue
                }
                delay[v] = 0
                val len = data.size
                val g = gain[v]
                val r = rate[v]
                var p = pos[v]
                var i = wait
                while (i < count) {
                    val idx = p.toInt()
                    if (idx >= len) break
                    val a = data[idx]
                    val b = if (idx + 1 < len) data[idx + 1] else 0f
                    out[i] += g * (a + (b - a) * (p - idx).toFloat())
                    p += r
                    i++
                }
                pos[v] = p
                if (p.toInt() >= len) {
                    clips[v] = null
                    active--
                }
            }
        }
        val master = masterGain
        for (i in 0 until count) out[i] = softLimit(out[i] * master)
    }
}

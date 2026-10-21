package com.andrewevmiller.tvlauncher.sound.synth

import com.andrewevmiller.tvlauncher.sound.Waveform
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val SAMPLE_RATE = 48_000

/**
 * One note, with the parameters of the mockup's `tri(f, {...})` call (defaults are the mockup's).
 * Times are seconds, frequencies Hz, [detune] and the voice spread are cents, [q] is the low-pass Q in dB
 * (Web Audio convention), [drive] is the per-sound value before the global x1.6 multiplier.
 */
data class VoiceSpec(
    val freq: Double,
    val glideTo: Double? = null,
    val atSec: Double = 0.0,
    val dur: Double = 0.1,
    val gain: Double = 0.03,
    val voices: Int = 2,
    val detune: Double = 9.0,
    val lpFrom: Double = 7000.0,
    val lpTo: Double = 2500.0,
    val q: Double = 1.5,
    val drive: Double = 6.0,
    val fold: Double = 2.2,
    val crush: Double = 0.0,
    val click: Double = 0.0,
)

/** Optional read-out of what the master stage did to a render. */
class RenderInfo {
    /** Highest absolute sample after the master chain, before the safety ceiling. */
    var peakBeforeCeiling: Double = 0.0

    /** True if the render would have exceeded -1 dBFS and the soft ceiling was applied. */
    var ceilingApplied: Boolean = false
}

/**
 * Renders [VoiceSpec]s to mono float samples the way the mockup's Web Audio graph does:
 * `oscillators -> sum (1/sqrt(voices)) -> shaper (4x) -> low-pass sweep -> envelope -> bus`, a noise click
 * straight onto the bus, then the master chain (high-pass 70 Hz, compressor, x0.8).
 *
 * Pure Kotlin, offline, deterministic: the output depends only on [waveform], [seed], [sampleRate] and the
 * specs (the seed drives voice start jitter, the noise-voice read offset and the noise buffers). Instances
 * hold only immutable noise buffers and a table cache, so [render] may be called from several threads.
 */
class VoiceRenderer(
    val waveform: Waveform,
    val seed: Long = 1L,
    val sampleRate: Int = SAMPLE_RATE,
) {
    private val isNoise = waveform == Waveform.WHITE_NOISE || waveform == Waveform.PINK_NOISE

    /** 1 s loopable noise for the noise waveforms (white and pink come from the same random sequence). */
    private val noiseLoop: FloatArray? = when (waveform) {
        Waveform.WHITE_NOISE -> NoiseBuffers.loop(pink = false, seed = seed xor LOOP_SALT, length = sampleRate)
        Waveform.PINK_NOISE -> NoiseBuffers.loop(pink = true, seed = seed xor LOOP_SALT, length = sampleRate)
        else -> null
    }

    /** The mockup's 50 ms white buffer behind every attack click. */
    private val clickNoise: FloatArray = NoiseBuffers.burst(seed xor CLICK_SALT, (CLICK_BUFFER_SECONDS * sampleRate).roundToInt())

    private data class TableKey(val drive: Double, val fold: Double, val crush: Double)

    private val tables = ConcurrentHashMap<TableKey, FloatArray>()

    /**
     * All specs are mixed onto one bus, then the master chain runs. Returns mono samples; the length covers
     * every spec's `atSec + dur + 0.03 s` plus a short master tail. The compressor's look-ahead delay is
     * removed, so sample 0 is the start of the first note.
     */
    fun render(specs: List<VoiceSpec>): FloatArray = renderWithInfo(specs, null)

    /** Same as [render], and fills [info] (if not null) with what the master stage did. */
    fun renderWithInfo(specs: List<VoiceSpec>, info: RenderInfo?): FloatArray {
        var busLen = 0
        var maxVoice = 0
        for (i in specs.indices) {
            val s = specs[i]
            if (!isRenderable(s)) continue
            val nv = voiceLength(s)
            busLen = max(busLen, startSample(s) + nv)
            maxVoice = max(maxVoice, nv)
        }
        if (busLen == 0) {
            info?.let { it.peakBeforeCeiling = 0.0; it.ceilingApplied = false }
            return FloatArray(0)
        }
        val outLen = busLen + ceil(MASTER_TAIL_SECONDS * sampleRate).toInt()
        val delay = Math.round(DynamicsCompressor.PRE_DELAY_SECONDS * sampleRate).toInt()
        val bus = DoubleArray(Master.paddedLength(outLen + delay))
        val scratch = DoubleArray(maxVoice)
        for (i in specs.indices) {
            val s = specs[i]
            if (isRenderable(s)) renderVoice(s, i, bus, scratch)
        }
        return Master.process(bus, outLen, sampleRate, info)
    }

    private fun isRenderable(s: VoiceSpec): Boolean = s.freq > 0.0 && s.dur > 0.0 && s.atSec >= 0.0

    private fun voiceLength(s: VoiceSpec): Int = ceil((s.dur + OSC_STOP_AFTER_DUR) * sampleRate - 1e-6).toInt() // minus epsilon: 0.13 * 48000 must not become 6241

    private fun startSample(s: VoiceSpec): Int = (s.atSec * sampleRate).roundToInt()

    private fun tableFor(s: VoiceSpec): FloatArray {
        val key = TableKey(s.drive, s.fold, s.crush)
        tables[key]?.let { return it }
        if (tables.size > MAX_CACHED_TABLES) tables.clear()
        val built = Shaper.buildTable(s.fold, s.drive, s.crush)
        tables[key] = built
        return built
    }

    private fun renderVoice(spec: VoiceSpec, index: Int, bus: DoubleArray, sum: DoubleArray) {
        val sr = sampleRate
        val nv = voiceLength(spec)
        val s0 = startSample(spec)
        val rng = XorShift64(seed * GOLDEN + index)
        val glideTo = spec.glideTo ?: Double.NaN
        val glideSamples = spec.dur * sr
        java.util.Arrays.fill(sum, 0, nv, 0.0)

        // 1. Sources into `sum`.
        if (isNoise) {
            val loop = noiseLoop!!
            var idx = (rng.nextDouble() * 0.5 * sr).toInt()
            for (n in 0 until nv) {
                sum[n] = loop[idx].toDouble()
                if (++idx == loop.size) idx = 0
            }
            // A narrow resonant band-pass at the note's pitch (following the glide) makes the noise sing it.
            val bp = Biquad()
            bp.sweep(
                Biquad.Mode.BANDPASS_LINEAR, sum, 0, nv, spec.freq,
                if (glideTo.isNaN()) spec.freq else glideTo, glideSamples, NOISE_Q, sr,
            )
            val makeup = if (waveform == Waveform.WHITE_NOISE) WHITE_MAKEUP else PINK_MAKEUP
            for (n in 0 until nv) sum[n] *= makeup
        } else {
            val isSuper = waveform == Waveform.SUPERSAW
            val voices = if (isSuper) SUPERSAW_VOICES else max(1, spec.voices)
            val detune = if (isSuper) max(spec.detune, SUPERSAW_MIN_DETUNE) else spec.detune
            val type = when (waveform) {
                Waveform.SINE -> OscType.SINE
                Waveform.TRIANGLE -> OscType.TRIANGLE
                Waveform.SQUARE -> OscType.SQUARE
                else -> OscType.SAW
            }
            val scale = 1.0 / sqrt(voices.toDouble())
            for (v in 0 until voices) {
                val jitter = (rng.nextDouble() * VOICE_JITTER_SECONDS * sr).toInt()
                Oscillators.add(type, sum, jitter, nv, spec.freq, glideTo, glideSamples, spreadCents(v, voices, detune), sr, scale)
            }
        }

        // 2. Wavefold -> tanh -> crush, 4x oversampled.
        Oversampler4x(tableFor(spec)).process(sum, 0, nv)

        // 3. Low-pass sweep (Q in dB).
        val lp = Biquad()
        lp.sweep(Biquad.Mode.LOWPASS_DB, sum, 0, nv, spec.lpFrom, max(LP_FLOOR_HZ, spec.lpTo), glideSamples, spec.q, sr)

        // 4. Envelope into the bus: exponential 0.0001 -> peak over 3 ms, -> 0.0001 at `dur`, then held.
        val peak = if (isNoise) spec.gain * NOISE_ENV_BOOST else spec.gain
        mixEnvelope(sum, nv, bus, s0, spec.dur, peak)

        // 5. Attack click, straight onto the bus.
        if (spec.click > ENV_FLOOR) mixClick(bus, s0, spec.click)
    }

    private fun mixEnvelope(src: DoubleArray, nv: Int, bus: DoubleArray, s0: Int, dur: Double, peak: Double) {
        if (peak <= 0.0) return
        val sr = sampleRate
        val attack = min(ENV_ATTACK_SECONDS, dur)
        val attackSamples = attack * sr
        val durSamples = dur * sr
        var n = 0
        var e = ENV_FLOOR
        val rise = (peak / ENV_FLOOR).pow(1.0 / attackSamples)
        while (n < nv && n < attackSamples) {
            bus[s0 + n] += src[n] * e
            e *= rise
            n++
        }
        val fallSamples = durSamples - attackSamples
        if (fallSamples > 0.0) {
            val fall = (ENV_FLOOR / peak).pow(1.0 / fallSamples)
            e = peak * fall.pow(n - attackSamples)
            while (n < nv && n < durSamples) {
                bus[s0 + n] += src[n] * e
                e *= fall
                n++
            }
        }
        while (n < nv) {
            bus[s0 + n] += src[n] * ENV_FLOOR
            n++
        }
    }

    private fun mixClick(bus: DoubleArray, s0: Int, click: Double) {
        val sr = sampleRate
        val len = min(clickNoise.size, (CLICK_STOP_SECONDS * sr).roundToInt())
        val bp = Biquad()
        bp.setBandpass(CLICK_HZ, CLICK_Q, sr)
        val decaySamples = CLICK_DECAY_SECONDS * sr
        val decay = (ENV_FLOOR / click).pow(1.0 / decaySamples)
        var e = click
        for (n in 0 until len) {
            val g = if (n < decaySamples) e else ENV_FLOOR // held at the floor until the burst is stopped
            bus[s0 + n] += bp.process(clickNoise[n].toDouble()) * g
            e *= decay
        }
    }

    companion object {
        private const val GOLDEN = -7046029254386353131L // 0x9E3779B97F4A7C15
        private const val LOOP_SALT = 0x6E6F697365L // "noise"
        private const val CLICK_SALT = 0x636C69636BL // "click"

        /** Oscillators stop 30 ms after `dur`, as in the mockup. */
        const val OSC_STOP_AFTER_DUR = 0.03

        /** Room after the last oscillator for the master filter to settle. */
        const val MASTER_TAIL_SECONDS = 0.05

        private const val VOICE_JITTER_SECONDS = 0.002
        private const val SUPERSAW_VOICES = 7
        private const val SUPERSAW_MIN_DETUNE = 22.0
        private const val NOISE_Q = 30.0
        private const val WHITE_MAKEUP = 24.0
        private const val PINK_MAKEUP = 36.0
        private const val NOISE_ENV_BOOST = 1.8
        private const val LP_FLOOR_HZ = 60.0
        private const val ENV_FLOOR = 0.0001
        private const val ENV_ATTACK_SECONDS = 0.003
        private const val CLICK_HZ = 3200.0
        private const val CLICK_Q = 1.2
        private const val CLICK_DECAY_SECONDS = 0.012
        private const val CLICK_STOP_SECONDS = 0.02
        private const val CLICK_BUFFER_SECONDS = 0.05
        private const val MAX_CACHED_TABLES = 64

        /** Detune of voice [i] of [voices]: linear -1..+1 across the voices times [detune] cents; one voice is centred. */
        internal fun spreadCents(i: Int, voices: Int, detune: Double): Double =
            if (voices <= 1) 0.0 else (-1.0 + 2.0 * i / (voices - 1)) * detune
    }
}

package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

private fun linearToDb(x: Double): Double = if (x > 0.0) 20.0 * log10(x) else -1000.0
private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

/**
 * Web Audio's DynamicsCompressorNode, ported from Chromium's DynamicsCompressorKernel (mono): a soft-knee
 * static curve, a detector with a 2.5 ms saturation release, an attack envelope derived from how far the
 * gain has to fall, a four-zone adaptive release, a sin() warp of the gain, 6 ms of look-ahead, and
 * Chromium's automatic make-up gain `(1 / staticGainAt0dBFS) ^ 0.6`.
 *
 * Defaults are the mockup's master: threshold -20 dB, ratio 6, attack 2 ms, release 120 ms, knee 30 dB.
 * Input is processed in 32-frame divisions, as in Chromium, so [process] needs a multiple of 32 frames.
 * The output is delayed by [preDelayFrames] (the look-ahead); callers read from that offset.
 */
internal class DynamicsCompressor(
    private val sampleRate: Int,
    private val thresholdDb: Double = -20.0,
    private val kneeDb: Double = 30.0,
    private val ratio: Double = 6.0,
    attackSeconds: Double = 0.002,
    releaseSeconds: Double = 0.12,
) {
    val preDelayFrames: Int = Math.round(PRE_DELAY_SECONDS * sampleRate).toInt()

    private val linearThreshold = dbToLinear(thresholdDb)
    private val kneeThresholdDb = thresholdDb + kneeDb
    private val kneeThreshold = dbToLinear(kneeThresholdDb)
    private val slope = 1.0 / ratio
    private val k: Double
    private val yKneeThresholdDb: Double

    /** Output level of the static curve for a 0 dBFS input (before make-up). */
    val staticGainAt0dBFS: Double

    /** Chromium's automatic make-up gain. */
    val makeupGain: Double

    private val attackFrames = max(0.001, attackSeconds) * sampleRate
    private val kA: Double
    private val kB: Double
    private val kC: Double
    private val kD: Double
    private val kE: Double
    private val satReleaseFrames = SAT_RELEASE_SECONDS * sampleRate

    // Ring size: the next power of two above the look-ahead.
    private val delayMask = run {
        var size = 1
        while (size <= preDelayFrames) size = size shl 1
        size - 1
    }
    private val delayBuf = DoubleArray(delayMask + 1)
    private var readIndex = 0
    private var writeIndex = preDelayFrames and delayMask

    private var detectorAverage = 1.0
    private var compressorGain = 1.0
    private var maxAttackCompressionDiffDb = -1.0

    init {
        k = kAtSlope(slope)
        yKneeThresholdDb = linearToDb(kneeCurve(kneeThreshold, k))
        staticGainAt0dBFS = saturate(1.0, k)
        makeupGain = (1.0 / staticGainAt0dBFS).pow(0.6)

        val releaseFrames = releaseSeconds * sampleRate
        val y1 = releaseFrames * RELEASE_ZONE_1
        val y2 = releaseFrames * RELEASE_ZONE_2
        val y3 = releaseFrames * RELEASE_ZONE_3
        val y4 = releaseFrames * RELEASE_ZONE_4
        // 4th-order polynomial through (0, y1) (1, y2) (2, y3) (3, y4); coefficients from Chromium.
        kA = 0.9999999999999998 * y1 + 1.8432219684323923e-16 * y2 - 1.9373394351676423e-16 * y3 + 8.824516011816245e-18 * y4
        kB = -1.5788320352845888 * y1 + 2.3305837032074286 * y2 - 0.9141194204840429 * y3 + 0.1623677525612032 * y4
        kC = 0.5334142869106424 * y1 - 1.272736789213631 * y2 + 0.9258856042207512 * y3 - 0.18656310191776226 * y4
        kD = 0.08783463138207234 * y1 - 0.1694162967925622 * y2 + 0.08588057951595272 * y3 - 0.00429891410546283 * y4
        kE = -0.042416883008123074 * y1 + 0.1115693827987602 * y2 - 0.09764676325265872 * y3 + 0.028494263462021576 * y4
    }

    /** The adaptive release length in frames at polynomial position x in [0, 3] (exposed for tests). */
    internal fun releasePolynomial(x: Double): Double {
        val x2 = x * x
        return kA + kB * x + kC * x2 + kD * x2 * x + kE * x2 * x2
    }

    /** Static compression curve: input level -> output level, both linear (before make-up). */
    fun saturate(x: Double): Double = saturate(x, k)

    private fun kneeCurve(x: Double, kk: Double): Double =
        if (x < linearThreshold) x else linearThreshold + (1.0 - exp(-kk * (x - linearThreshold))) / kk

    private fun saturate(x: Double, kk: Double): Double = when {
        x < linearThreshold -> x
        x < kneeThreshold -> kneeCurve(x, kk)
        else -> dbToLinear(yKneeThresholdDb + slope * (linearToDb(x) - kneeThresholdDb))
    }

    private fun slopeAt(x: Double, kk: Double): Double {
        if (x < linearThreshold) return 1.0
        val x2 = x * 1.001
        val xDb = linearToDb(x)
        val x2Db = linearToDb(x2)
        val yDb = linearToDb(kneeCurve(x, kk))
        val y2Db = linearToDb(kneeCurve(x2, kk))
        return (y2Db - yDb) / (x2Db - xDb)
    }

    private fun kAtSlope(desiredSlope: Double): Double {
        val x = dbToLinear(thresholdDb + kneeDb)
        var minK = 0.1
        var maxK = 10000.0
        var kk = 5.0
        for (i in 0 until 15) {
            if (slopeAt(x, kk) < desiredSlope) maxK = kk else minK = kk
            kk = sqrt(minK * maxK)
        }
        return kk
    }

    /**
     * Compresses `input[0 until frames]` into `output` (may be the same array). `frames` must be a
     * multiple of 32. The result is delayed by [preDelayFrames].
     */
    fun process(input: DoubleArray, output: DoubleArray, frames: Int) {
        require(frames % DIVISION == 0) { "frames must be a multiple of $DIVISION" }
        var det = detectorAverage
        var gain = compressorGain
        var maxAttack = maxAttackCompressionDiffDb
        var rd = readIndex
        var wr = writeIndex
        val mask = delayMask
        val buf = delayBuf
        var frame = 0
        val divisions = frames / DIVISION
        for (d in 0 until divisions) {
            if (det.isNaN() || det.isInfinite()) det = 1.0
            // Pre-warp so we get the desired gain after the sin() warp below.
            val scaledDesiredGain = asin(det) / (0.5 * PI)
            val releasing = scaledDesiredGain > gain
            var diffDb = linearToDb(gain / scaledDesiredGain)
            val envelopeRate: Double
            if (releasing) {
                maxAttack = -1.0
                if (diffDb.isNaN() || diffDb.isInfinite()) diffDb = -1.0
                val x = 0.25 * (min(0.0, max(-12.0, diffDb)) + 12.0)
                val releaseFrames = releasePolynomial(x)
                envelopeRate = dbToLinear(SPACING_DB / releaseFrames)
            } else {
                if (diffDb.isNaN() || diffDb.isInfinite()) diffDb = 1.0
                if (maxAttack == -1.0 || maxAttack < diffDb) maxAttack = diffDb
                val effAttenDiffDb = max(0.5, maxAttack)
                val x = 0.25 / effAttenDiffDb
                envelopeRate = 1.0 - x.pow(1.0 / attackFrames)
            }
            for (i in 0 until DIVISION) {
                val sample = input[frame]
                buf[wr] = sample
                val absInput = abs(sample)

                // Detector: shaped power of the undelayed input through the static curve.
                val shaped = saturate(absInput, k)
                val attenuation = if (absInput <= 0.0001) 1.0 else shaped / absInput
                var attenuationDb = -linearToDb(attenuation)
                attenuationDb = max(2.0, attenuationDb)
                val satReleaseRate = dbToLinear(attenuationDb / satReleaseFrames) - 1.0
                val rate = if (attenuation > det) satReleaseRate else 1.0
                det += (attenuation - det) * rate
                det = min(1.0, det)
                if (det.isNaN() || det.isInfinite()) det = 1.0

                // Exponential approach to the desired gain.
                if (envelopeRate < 1.0) {
                    gain += (scaledDesiredGain - gain) * envelopeRate
                } else {
                    gain = min(1.0, gain * envelopeRate)
                }
                val postWarp = sin(0.5 * PI * gain)

                output[frame] = buf[rd] * (makeupGain * postWarp)
                frame++
                rd = (rd + 1) and mask
                wr = (wr + 1) and mask
            }
        }
        detectorAverage = det
        compressorGain = gain
        maxAttackCompressionDiffDb = maxAttack
        readIndex = rd
        writeIndex = wr
    }

    companion object {
        const val DIVISION = 32
        const val PRE_DELAY_SECONDS = 0.006
        private const val SAT_RELEASE_SECONDS = 0.0025
        private const val SPACING_DB = 5.0
        private const val RELEASE_ZONE_1 = 0.09
        private const val RELEASE_ZONE_2 = 0.16
        private const val RELEASE_ZONE_3 = 0.42
        private const val RELEASE_ZONE_4 = 0.98
    }
}

/** The mockup's master bus: high-pass 70 Hz (Q 1 dB) -> compressor -> x0.8, then a safety ceiling. */
internal object Master {
    const val HIGHPASS_HZ = 70.0
    const val HIGHPASS_Q_DB = 1.0
    const val OUTPUT_GAIN = 0.8

    /** -1 dBFS. */
    val CEILING: Double = dbToLinear(-1.0)

    /** The ceiling starts bending the signal here (a fraction of [CEILING]); below it nothing changes. */
    private const val CEILING_KNEE = 0.7

    /** Padding that makes a buffer a whole number of compressor divisions. */
    fun paddedLength(frames: Int): Int = (frames + DynamicsCompressor.DIVISION - 1) / DynamicsCompressor.DIVISION * DynamicsCompressor.DIVISION

    /**
     * Runs the master chain over `bus` (length a multiple of 32, voices already mixed in, zero-padded by at
     * least the compressor look-ahead) and returns `outLength` samples, with the look-ahead delay removed so the
     * first sample lines up with the first sample of the bus.
     */
    fun process(bus: DoubleArray, outLength: Int, sampleRate: Int, info: RenderInfo?): FloatArray {
        val hp = Biquad()
        hp.setHighpass(HIGHPASS_HZ, HIGHPASS_Q_DB, sampleRate)
        hp.processBlock(bus, 0, bus.size)
        val comp = DynamicsCompressor(sampleRate)
        comp.process(bus, bus, bus.size)
        val delay = comp.preDelayFrames
        require(bus.size >= outLength + delay) { "bus too short for look-ahead" }

        var peak = 0.0
        for (i in 0 until outLength) {
            val v = bus[i + delay] * OUTPUT_GAIN
            bus[i + delay] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        val ceiling = CEILING
        val needsCeiling = peak > ceiling
        val out = FloatArray(outLength)
        if (needsCeiling) {
            val knee = ceiling * CEILING_KNEE
            val room = ceiling - knee
            for (i in 0 until outLength) {
                val v = bus[i + delay]
                val a = abs(v)
                out[i] = if (a <= knee) v.toFloat() else {
                    val s = knee + room * tanh((a - knee) / room)
                    (if (v < 0.0) -s else s).toFloat()
                }
            }
        } else {
            for (i in 0 until outLength) out[i] = bus[i + delay].toFloat()
        }
        if (info != null) {
            info.peakBeforeCeiling = peak
            info.ceilingApplied = needsCeiling
        }
        return out
    }
}

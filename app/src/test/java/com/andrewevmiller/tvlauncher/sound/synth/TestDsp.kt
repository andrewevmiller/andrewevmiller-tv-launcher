package com.andrewevmiller.tvlauncher.sound.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/** Measurement helpers shared by the synth tests: FFT, band power, zero-crossing frequency, RMS. */
internal object TestDsp {
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Welch power spectrum (Hann, 50% overlap), bins 0..seg/2, in units of power per bin. */
    fun welch(x: DoubleArray, seg: Int): DoubleArray {
        val win = DoubleArray(seg) { 0.5 - 0.5 * cos(2.0 * PI * it / seg) }
        val acc = DoubleArray(seg / 2 + 1)
        var count = 0
        var start = 0
        val re = DoubleArray(seg)
        val im = DoubleArray(seg)
        while (start + seg <= x.size) {
            for (i in 0 until seg) {
                re[i] = x[start + i] * win[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in 0..seg / 2) acc[k] += re[k] * re[k] + im[k] * im[k]
            count++
            start += seg / 2
        }
        for (k in acc.indices) acc[k] /= count
        return acc
    }

    /** Mean power per bin over [loHz, hiHz). */
    fun bandPower(spectrum: DoubleArray, seg: Int, sampleRate: Int, loHz: Double, hiHz: Double): Double {
        val lo = Math.ceil(loHz * seg / sampleRate).toInt()
        val hi = Math.ceil(hiHz * seg / sampleRate).toInt()
        var sum = 0.0
        for (k in lo until hi) sum += spectrum[k]
        return sum / (hi - lo)
    }

    fun db(power: Double): Double = 10.0 * log10(power)

    /** Least-squares slope of y against x. */
    fun slope(xs: DoubleArray, ys: DoubleArray): Double {
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return num / den
    }

    /**
     * Frequency from upward zero crossings, each located by linear interpolation, using the first and the last
     * crossing: error stays under about one sample over the whole measurement.
     */
    fun zeroCrossingFrequency(x: DoubleArray, sampleRate: Int, skip: Int = 0): Double {
        var first = Double.NaN
        var last = Double.NaN
        var count = 0
        for (i in skip + 1 until x.size) {
            if (x[i - 1] < 0.0 && x[i] >= 0.0) {
                val pos = i - 1 + (-x[i - 1]) / (x[i] - x[i - 1])
                if (first.isNaN()) first = pos
                last = pos
                count++
            }
        }
        require(count >= 3) { "too few crossings: $count" }
        return (count - 1) * sampleRate / (last - first)
    }

    fun cents(measured: Double, expected: Double): Double = 1200.0 * log2(measured / expected)

    fun rms(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / x.size)
    }

    fun peak(x: FloatArray): Double {
        var p = 0.0
        for (v in x) if (abs(v.toDouble()) > p) p = abs(v.toDouble())
        return p
    }

    fun rmsDb(x: FloatArray): Double = 20.0 * log10(rms(x))

    fun allFinite(x: FloatArray): Boolean {
        for (v in x) if (v.isNaN() || v.isInfinite()) return false
        return true
    }

    fun allFinite(x: DoubleArray): Boolean {
        for (v in x) if (v.isNaN() || v.isInfinite()) return false
        return true
    }
}

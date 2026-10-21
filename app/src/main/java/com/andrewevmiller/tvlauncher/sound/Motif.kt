package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Timbre of a launch motif, by app category. */
internal class Timbre(
    val voices: Int, val detune: Double, val drive: Double, val fold: Double, val crush: Int,
    val lpFrom: Double, val lpTo: Double, val q: Double, val click: Double,
)

/**
 * Per-launch variation: pitch in cents (about +-5) and level (about +-10%). Applied when the motif is
 * played, not when it is rendered, so one rendered buffer serves every launch.
 */
internal class Humanize(val cents: Double, val velocity: Double) {
    /** Playback rate for a buffer rendered at [rate] Hz, so that it sounds [cents] higher or lower. */
    fun playbackRate(rate: Int): Int = (rate * 2.0.pow(cents / 1200)).roundToInt()

    companion object {
        val NONE = Humanize(0.0, 1.0)

        /** Same draw order and ranges as the mockup: cents first, then level. */
        fun random(random: () -> Double): Humanize = Humanize(random() * 10 - 5, 0.9 + random() * 0.2)
    }
}

/**
 * The per-app launch motif. The generator (FNV-1a of the package name seeds mulberry32; A minor
 * pentatonic from A3) must match the mockup exactly, draw for draw, so the TV plays the tunes
 * auditioned there. See the golden vectors in docs/BUILD_PLAN.md section 8.3.
 */
internal object Motif {
    fun timbre(c: AppCategory) = when (c) {
        AppCategory.VIDEO -> Timbre(2, 14.0, 4.0, 0.0, 0, 2400.0, 900.0, 2.0, 0.015)
        AppCategory.MUSIC -> Timbre(3, 10.0, 5.0, 1.3, 0, 3200.0, 1100.0, 7.0, 0.02)
        AppCategory.GAMES -> Timbre(2, 6.0, 8.0, 0.0, 8, 7000.0, 3000.0, 1.5, 0.02)
        AppCategory.TOOLS -> Timbre(1, 0.0, 10.0, 2.0, 0, 5000.0, 2000.0, 1.5, 0.045)
        AppCategory.OTHER -> Timbre(2, 9.0, 6.0, 1.5, 0, 4000.0, 1500.0, 2.0, 0.02)
    }

    fun fnv1a(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in s) h = (h xor ch.code) * 0x01000193
        return h
    }

    fun mulberry32(seed0: Int): () -> Double {
        var seed = seed0
        return {
            seed += 0x6D2B79F5
            var t = seed
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    /** The notes of a motif: pentatonic degrees, the matching MIDI notes, the gap between them, and whether the last one is held. */
    class Plan(val degrees: List<Int>, val gapMs: Int, val hold: Boolean) {
        val midi: List<Int> get() = degrees.map { d -> 57 + SCALE[d % 5] + 12 * (d / 5) }
    }

    private val SCALE = intArrayOf(0, 3, 5, 7, 10)

    fun plan(pkg: String): Plan {
        val r = mulberry32(fnv1a(pkg))
        val count = if (r() < 0.3) 4 else 3
        var deg = floor(r() * 5).toInt()
        val degs = mutableListOf(deg)
        for (i in 1 until count) {
            deg += 1 + floor(r() * 2).toInt()
            degs += deg
        }
        val gap = (35 + r() * 35).roundToInt()
        val hold = r() < 0.5
        return Plan(degs, gap, hold)
    }

    fun voices(pkg: String, category: AppCategory, human: Humanize = Humanize.NONE): List<Voice> {
        val plan = plan(pkg)
        val freqs = plan.midi.map { m -> 440.0 * 2.0.pow((m - 69) / 12.0) }
        val tb = timbre(category)
        val cents = human.cents
        val vel = human.velocity
        val voices = freqs.mapIndexed { i, f ->
            val last = i == freqs.lastIndex
            Voice(
                f * 2.0.pow(cents / 1200), i * plan.gapMs / 1000.0, if (last) (if (plan.hold) 0.24 else 0.1) else 0.11, 0.026 * vel,
                voices = tb.voices, detune = tb.detune, fold = tb.fold, drive = tb.drive, crush = tb.crush,
                lpFrom = tb.lpFrom, lpTo = tb.lpTo, q = tb.q, click = if (i == 0) tb.click else 0.0,
            )
        }
        val sub = Voice(freqs[0] / 4, 0.0, 0.26, 0.03 * vel, voices = 1, detune = 0.0, fold = 0.0, drive = 3.0, lpFrom = 400.0, lpTo = 150.0)
        return voices + sub
    }
}

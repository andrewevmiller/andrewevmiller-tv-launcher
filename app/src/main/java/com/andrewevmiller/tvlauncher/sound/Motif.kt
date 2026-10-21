package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import kotlin.math.floor
import kotlin.math.pow

/*
 * Per-app launch motifs: a bit-exact port of the mockup's motifFor (docs/mockup/field-launcher-mockup.html,
 * lines 482-509) and BUILD_PLAN §8.2. Pure Kotlin so the golden vectors in §8.3 run on the JVM.
 * The order of r() draws is part of the contract: do not reorder them.
 */

private val MOTIF_SCALE = intArrayOf(0, 3, 5, 7, 10)  // A minor pentatonic
private const val MOTIF_ROOT_MIDI = 57                // A3
private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/**
 * FNV-1a, 32-bit, over UTF-16 code units (JS charCodeAt). Offset basis 0x811c9dc5, prime 0x01000193.
 * UInt arithmetic wraps modulo 2^32, which matches Math.imul.
 */
fun fnv1a32(input: String): UInt {
    var h = 0x811c9dc5u
    for (ch in input) {
        h = h xor ch.code.toUInt()
        h *= 0x01000193u
    }
    return h
}

/**
 * mulberry32, the mockup's PRNG. Int arithmetic wraps exactly like Math.imul; `ushr` is JS `>>>`.
 * [next] returns an unsigned 32-bit value divided by 2^32, so it lies in [0, 1).
 */
class Mulberry32(seed: UInt) {
    private var state: Int = seed.toInt()

    fun next(): Double {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
        val out = t xor (t ushr 14)
        return (out.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

/** Sound parameters for one category. Drive is BEFORE the global x1.6 multiplier (applied by the synth). */
data class Timbre(
    val label: String,
    val voices: Int,
    val detune: Double,
    val drive: Double,
    val fold: Double,
    val crush: Double,
    val lpFrom: Double,
    val lpTo: Double,
    val q: Double,
    val click: Double,
)

/** BUILD_PLAN §8.2 timbre table (mockup TIMBRE). */
fun timbreFor(category: AppCategory): Timbre = when (category) {
    AppCategory.VIDEO -> Timbre("warm", 2, 14.0, 4.0, 0.0, 0.0, 2400.0, 900.0, 2.0, 0.015)
    AppCategory.MUSIC -> Timbre("resonant", 3, 10.0, 5.0, 1.3, 0.0, 3200.0, 1100.0, 7.0, 0.02)
    AppCategory.GAMES -> Timbre("crushed", 2, 6.0, 8.0, 0.0, 8.0, 7000.0, 3000.0, 1.5, 0.02)
    AppCategory.TOOLS -> Timbre("clipped", 1, 0.0, 10.0, 2.0, 0.0, 5000.0, 2000.0, 1.5, 0.045)
    AppCategory.OTHER -> Timbre("plain", 2, 9.0, 6.0, 1.5, 0.0, 4000.0, 1500.0, 2.0, 0.02)
}

/**
 * A generated launch motif. [midi] is always strictly rising; [frequencies] is 440·2^((midi−69)/12).
 * [gapMs] is the spacing between note onsets; [hold] asks for a longer final note.
 */
data class Motif(
    val seed: UInt,
    val midi: List<Int>,
    val frequencies: List<Double>,
    val gapMs: Int,
    val hold: Boolean,
)

/**
 * The motif for an app. With [rerollCount] 0 the seed is fnv1a32(packageName); after a re-roll it is
 * fnv1a32(packageName + "#" + rerollCount). Category does not enter here; it only picks the timbre.
 */
fun motifFor(packageName: String, rerollCount: Int = 0): Motif {
    val seed = if (rerollCount == 0) fnv1a32(packageName) else fnv1a32(packageName + "#" + rerollCount)
    val r = Mulberry32(seed)

    val count = if (r.next() < 0.3) 4 else 3
    var deg = floor(r.next() * 5).toInt()
    val degs = ArrayList<Int>(count)
    degs.add(deg)
    for (i in 1 until count) {
        deg += 1 + floor(r.next() * 2).toInt()   // always rising
        degs.add(deg)
    }
    val gapMs = jsRound(35 + r.next() * 35)
    val hold = r.next() < 0.5

    // deg is never negative, so Int division equals the mockup's Math.floor(deg / 5).
    val midi = degs.map { d -> MOTIF_ROOT_MIDI + MOTIF_SCALE[d % 5] + 12 * (d / 5) }
    // Double division: (midi - 69) / 12 must not truncate.
    val frequencies = midi.map { m -> 440.0 * 2.0.pow((m - 69) / 12.0) }
    return Motif(seed, midi, frequencies, gapMs, hold)
}

/** Scientific pitch name, e.g. 64 → "E4" (octave = midi / 12 − 1). */
fun noteName(midi: Int): String = NOTE_NAMES[midi.mod(12)] + (midi.floorDiv(12) - 1)

/** JS Math.round: nearest integer, halves round toward +∞. Computed without floating-point rounding error. */
private fun jsRound(x: Double): Int {
    val f = floor(x)
    return (if (x - f >= 0.5) f + 1 else f).toInt()
}

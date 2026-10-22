package com.andrewevmiller.tvlauncher.tools.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.sound.Mulberry32
import com.andrewevmiller.tvlauncher.sound.fnv1a32
import com.andrewevmiller.tvlauncher.sound.motifFor
import java.util.Locale
import kotlin.math.abs

/** One of the nine packages of BUILD_PLAN 8.3, rendered under the category the brief names. */
internal data class MotifPackage(val packageName: String, val category: AppCategory)

internal val MOTIF_PACKAGES: List<MotifPackage> = listOf(
    MotifPackage("com.google.android.youtube.tv", AppCategory.VIDEO),
    MotifPackage("com.netflix.ninja", AppCategory.VIDEO),
    MotifPackage("com.plexapp.android", AppCategory.VIDEO),
    MotifPackage("com.spotify.tv.android", AppCategory.MUSIC),
    MotifPackage("com.aspiro.tidal", AppCategory.MUSIC),
    MotifPackage("com.limelight", AppCategory.GAMES),
    MotifPackage("com.retroarch", AppCategory.GAMES),
    MotifPackage("com.alphainventor.filemanager", AppCategory.TOOLS),
    MotifPackage("com.andrewevmiller.analyzer", AppCategory.TOOLS),
)

/** File names (without .wav) of the fixed sounds, in render order. The held tick is row 0, to A/B against tick-row0. */
internal val FIXED_SOUND_NAMES: List<String> = listOf(
    "tick-row0", "tick-row4", "tick-held-row0", "edge", "select", "back", "launch-fallback", "sleep", "wake",
)

internal const val PREVIEW_NAME = "preview"

internal fun motifName(p: MotifPackage): String = "motif-${p.packageName}"

/** Every file name rendered per waveform: 9 fixed sounds, the preview, and one motif per package. */
internal fun soundNames(): List<String> =
    FIXED_SOUND_NAMES + PREVIEW_NAME + MOTIF_PACKAGES.map(::motifName)

/** A motif's golden values from BUILD_PLAN 8.3 (the mockup's output, which the app must match bit for bit). */
internal data class GoldenMotif(
    val packageName: String,
    val seedHex: String,
    val midi: List<Int>,
    val gapMs: Int,
    val hold: Boolean,
)

internal val GOLDEN_MOTIFS: List<GoldenMotif> = listOf(
    GoldenMotif("com.google.android.youtube.tv", "aeeb3003", listOf(64, 69, 74, 79), 59, false),
    GoldenMotif("com.netflix.ninja", "cf47f8ae", listOf(57, 60, 62, 64), 62, false),
    GoldenMotif("com.plexapp.android", "b14e01c1", listOf(62, 64, 69, 74), 56, true),
    GoldenMotif("com.spotify.tv.android", "87686563", listOf(64, 69, 72, 74), 59, false),
    GoldenMotif("com.aspiro.tidal", "e3c340c6", listOf(60, 64, 69), 45, true),
    GoldenMotif("com.limelight", "ede9906b", listOf(64, 69, 72, 76), 48, false),
    GoldenMotif("com.retroarch", "a899f578", listOf(62, 64, 67, 72), 44, true),
    GoldenMotif("com.alphainventor.filemanager", "1c4fa632", listOf(64, 69, 72), 60, false),
    GoldenMotif("com.andrewevmiller.analyzer", "d8b6682d", listOf(60, 64, 67), 55, true),
)

/**
 * The BUILD_PLAN 8.3 golden vectors, checked against the app's own code before anything is rendered.
 * Returns one line per mismatch; empty means the port is bit-exact on every checked value.
 */
internal fun goldenProblems(): List<String> {
    val problems = ArrayList<String>()
    if (fnv1a32("") != 0x811c9dc5u) problems += "fnv1a32(\"\") is not 0x811c9dc5"
    if (fnv1a32("a") != 0xe40c292cu) problems += "fnv1a32(\"a\") is not 0xe40c292c"

    val prng = Mulberry32(1u)
    for (expected in doubleArrayOf(0.6270739406, 0.0027357212, 0.5274470400)) {
        val got = prng.next()
        if (abs(got - expected) > 1e-9) problems += "mulberry32(1) gave $got, want $expected"
    }

    for (g in GOLDEN_MOTIFS) {
        val motif = motifFor(g.packageName)
        val seed = String.format(Locale.ROOT, "%08x", motif.seed.toLong())
        if (seed != g.seedHex) problems += "${g.packageName}: seed $seed, want ${g.seedHex}"
        if (motif.midi != g.midi) problems += "${g.packageName}: notes ${motif.midi}, want ${g.midi}"
        if (motif.gapMs != g.gapMs) problems += "${g.packageName}: gap ${motif.gapMs} ms, want ${g.gapMs}"
        if (motif.hold != g.hold) problems += "${g.packageName}: hold ${motif.hold}, want ${g.hold}"
    }
    return problems
}

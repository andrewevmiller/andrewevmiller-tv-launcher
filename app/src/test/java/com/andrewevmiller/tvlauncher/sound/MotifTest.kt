package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden vectors from docs/BUILD_PLAN.md §8.2–§8.3, cross-checked against the mockup's JS (motifFor). */
class MotifTest {

    private data class Golden(
        val pkg: String,
        val seed: UInt,
        val notes: List<String>,
        val gapMs: Int,
        val hold: Boolean,
    )

    private val goldenMotifs = listOf(
        Golden("com.google.android.youtube.tv", 0xaeeb3003u, listOf("E4", "A4", "D5", "G5"), 59, false),
        Golden("com.netflix.ninja", 0xcf47f8aeu, listOf("A3", "C4", "D4", "E4"), 62, false),
        Golden("com.plexapp.android", 0xb14e01c1u, listOf("D4", "E4", "A4", "D5"), 56, true),
        Golden("com.spotify.tv.android", 0x87686563u, listOf("E4", "A4", "C5", "D5"), 59, false),
        Golden("com.aspiro.tidal", 0xe3c340c6u, listOf("C4", "E4", "A4"), 45, true),
        Golden("com.limelight", 0xede9906bu, listOf("E4", "A4", "C5", "E5"), 48, false),
        Golden("com.retroarch", 0xa899f578u, listOf("D4", "E4", "G4", "C5"), 44, true),
        Golden("com.alphainventor.filemanager", 0x1c4fa632u, listOf("E4", "A4", "C5"), 60, false),
        Golden("com.andrewevmiller.analyzer", 0xd8b6682du, listOf("C4", "E4", "G4"), 55, true),
    )

    @Test
    fun fnv1aMatchesReferenceValues() {
        assertEquals(0x811c9dc5u, fnv1a32(""))
        assertEquals(0xe40c292cu, fnv1a32("a"))
    }

    @Test
    fun mulberry32MatchesMockupForSeedOne() {
        val r = Mulberry32(1u)
        assertEquals(0.6270739406, r.next(), 1e-9)
        assertEquals(0.0027357212, r.next(), 1e-9)
        assertEquals(0.5274470400, r.next(), 1e-9)
    }

    @Test
    fun mulberry32StaysInUnitInterval() {
        val r = Mulberry32(0xffffffffu)
        repeat(10_000) {
            val v = r.next()
            assertTrue("out of range: $v", v >= 0.0 && v < 1.0)
        }
    }

    @Test
    fun everyGoldenMotifMatchesTheBuildPlanTable() {
        for (g in goldenMotifs) {
            val m = motifFor(g.pkg)
            assertEquals("seed ${g.pkg}", g.seed, m.seed)
            assertEquals("notes ${g.pkg}", g.notes, m.midi.map(::noteName))
            assertEquals("gap ${g.pkg}", g.gapMs, m.gapMs)
            assertEquals("hold ${g.pkg}", g.hold, m.hold)
        }
    }

    @Test
    fun goldenMidiValuesAreTheTableValues() {
        assertEquals(listOf(64, 69, 74, 79), motifFor("com.google.android.youtube.tv").midi)
        assertEquals(listOf(57, 60, 62, 64), motifFor("com.netflix.ninja").midi)
        assertEquals(listOf(62, 64, 69, 74), motifFor("com.plexapp.android").midi)
        assertEquals(listOf(64, 69, 72, 74), motifFor("com.spotify.tv.android").midi)
        assertEquals(listOf(60, 64, 69), motifFor("com.aspiro.tidal").midi)
        assertEquals(listOf(64, 69, 72, 76), motifFor("com.limelight").midi)
        assertEquals(listOf(62, 64, 67, 72), motifFor("com.retroarch").midi)
        assertEquals(listOf(64, 69, 72), motifFor("com.alphainventor.filemanager").midi)
        assertEquals(listOf(60, 64, 67), motifFor("com.andrewevmiller.analyzer").midi)
    }

    @Test
    fun frequenciesFollowEqualTemperamentFromA4() {
        val m = motifFor("com.plexapp.android")   // D4 E4 A4 D5
        assertEquals(listOf(62, 64, 69, 74), m.midi)
        assertEquals(293.6647679, m.frequencies[0], 1e-6)   // D4
        assertEquals(329.6275569, m.frequencies[1], 1e-6)   // E4
        assertEquals(440.0, m.frequencies[2], 1e-9)         // A4
        assertEquals(587.3295358, m.frequencies[3], 1e-6)   // D5
    }

    @Test
    fun motifsAlwaysRiseAndStayInTheAMinorPentatonic() {
        val packages = goldenMotifs.map { it.pkg } + (0 until 300).map { "com.example.app$it" }
        val pentatonicPitchClasses = setOf(9, 0, 2, 4, 7)   // A C D E G
        for (pkg in packages) {
            for (reroll in 0..3) {
                val m = motifFor(pkg, reroll)
                assertTrue("note count for $pkg#$reroll", m.midi.size == 3 || m.midi.size == 4)
                m.midi.zipWithNext().forEach { (a, b) -> assertTrue("not rising for $pkg#$reroll: $a -> $b", a < b) }
                m.midi.forEach { assertTrue("off scale for $pkg#$reroll: $it", it % 12 in pentatonicPitchClasses) }
                assertTrue("gap for $pkg#$reroll", m.gapMs in 35..70)
                assertEquals(m.midi.size, m.frequencies.size)
            }
        }
    }

    @Test
    fun defaultRerollCountIsTheUnrolledMotif() {
        assertEquals(motifFor("com.netflix.ninja"), motifFor("com.netflix.ninja", 0))
    }

    @Test
    fun rerollUsesTheHashSuffixSeed() {
        assertEquals(fnv1a32("com.plexapp.android#1"), motifFor("com.plexapp.android", 1).seed)
        assertEquals(0xa6a8c6d5u, motifFor("com.plexapp.android", 1).seed)
    }

    @Test
    fun rerollChangesTheMotifDeterministically() {
        val base = motifFor("com.plexapp.android")
        val rerolled = motifFor("com.plexapp.android", 1)
        assertNotEquals(base, rerolled)
        assertEquals(rerolled, motifFor("com.plexapp.android", 1))
        // Mockup-derived reroll vectors (same generator, seed fnv1a(pkg + "#" + n)).
        assertEquals(listOf(57, 60, 64, 69), rerolled.midi)
        assertEquals(46, rerolled.gapMs)
        assertEquals(false, rerolled.hold)
        val second = motifFor("com.plexapp.android", 2)
        assertEquals(0xa3a8c21cu, second.seed)
        assertEquals(listOf(60, 64, 67), second.midi)
        assertEquals(46, second.gapMs)
        assertEquals(true, second.hold)
        val ninja = motifFor("com.netflix.ninja", 1)
        assertEquals(0xff11d6b2u, ninja.seed)
        assertEquals(listOf(60, 64, 67), ninja.midi)
        assertEquals(37, ninja.gapMs)
        assertEquals(true, ninja.hold)
        val analyzer = motifFor("com.andrewevmiller.analyzer", 3)
        assertEquals(0xe383a3bbu, analyzer.seed)
        assertEquals(listOf(57, 62, 64), analyzer.midi)
        assertEquals(47, analyzer.gapMs)
        assertEquals(true, analyzer.hold)
    }

    @Test
    fun timbreTableMatchesTheBuildPlan() {
        assertEquals(Timbre("warm", 2, 14.0, 4.0, 0.0, 0.0, 2400.0, 900.0, 2.0, 0.015), timbreFor(AppCategory.VIDEO))
        assertEquals(Timbre("resonant", 3, 10.0, 5.0, 1.3, 0.0, 3200.0, 1100.0, 7.0, 0.02), timbreFor(AppCategory.MUSIC))
        assertEquals(Timbre("crushed", 2, 6.0, 8.0, 0.0, 8.0, 7000.0, 3000.0, 1.5, 0.02), timbreFor(AppCategory.GAMES))
        assertEquals(Timbre("clipped", 1, 0.0, 10.0, 2.0, 0.0, 5000.0, 2000.0, 1.5, 0.045), timbreFor(AppCategory.TOOLS))
        assertEquals(Timbre("plain", 2, 9.0, 6.0, 1.5, 0.0, 4000.0, 1500.0, 2.0, 0.02), timbreFor(AppCategory.OTHER))
    }

    @Test
    fun noteNamesUseScientificPitch() {
        assertEquals("A3", noteName(57))
        assertEquals("C4", noteName(60))
        assertEquals("C#4", noteName(61))
        assertEquals("E4", noteName(64))
        assertEquals("A4", noteName(69))
        assertEquals("C5", noteName(72))
        assertEquals("E5", noteName(76))
        assertEquals("B3", noteName(59))
    }
}

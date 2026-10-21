package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The motif generator must match the mockup draw for draw; these are the golden vectors from docs/BUILD_PLAN.md 8.3. */
class MotifTest {
    @Test fun fnv1aMatchesTheReferenceValues() {
        assertEquals(0x811c9dc5.toInt(), Motif.fnv1a(""))
        assertEquals(0xe40c292c.toInt(), Motif.fnv1a("a"))
    }

    @Test fun mulberry32MatchesTheReferenceValues() {
        val r = Motif.mulberry32(1)
        assertEquals(0.6270739406, r(), 1e-9)
        assertEquals(0.0027357212, r(), 1e-9)
        assertEquals(0.5274470400, r(), 1e-9)
    }

    private class Golden(val pkg: String, val seed: String, val midi: List<Int>, val gap: Int, val hold: Boolean)

    private val golden = listOf(
        Golden("com.google.android.youtube.tv", "aeeb3003", listOf(64, 69, 74, 79), 59, false),
        Golden("com.netflix.ninja", "cf47f8ae", listOf(57, 60, 62, 64), 62, false),
        Golden("com.plexapp.android", "b14e01c1", listOf(62, 64, 69, 74), 56, true),
        Golden("com.spotify.tv.android", "87686563", listOf(64, 69, 72, 74), 59, false),
        Golden("com.aspiro.tidal", "e3c340c6", listOf(60, 64, 69), 45, true),
        Golden("com.limelight", "ede9906b", listOf(64, 69, 72, 76), 48, false),
        Golden("com.retroarch", "a899f578", listOf(62, 64, 67, 72), 44, true),
        Golden("com.alphainventor.filemanager", "1c4fa632", listOf(64, 69, 72), 60, false),
        Golden("com.andrewevmiller.analyzer", "d8b6682d", listOf(60, 64, 67), 55, true),
    )

    @Test fun everyGoldenMotifMatches() {
        for (g in golden) {
            assertEquals("seed of ${g.pkg}", g.seed, "%08x".format(Motif.fnv1a(g.pkg)))
            val plan = Motif.plan(g.pkg)
            assertEquals("notes of ${g.pkg}", g.midi, plan.midi)
            assertEquals("gap of ${g.pkg}", g.gap, plan.gapMs)
            assertEquals("hold of ${g.pkg}", g.hold, plan.hold)
        }
    }

    @Test fun theSameAppAlwaysGetsTheSameTune() {
        val a = Motif.voices("com.netflix.ninja", AppCategory.VIDEO)
        val b = Motif.voices("com.netflix.ninja", AppCategory.VIDEO)
        assertEquals(a.map { it.f }, b.map { it.f })
        assertEquals(a.map { it.at }, b.map { it.at })
    }

    @Test fun timbresMatchTheSpecTable() {
        // docs/BUILD_PLAN.md 8.2: voices, detune, drive, fold, crush, lp from -> to, Q, click
        fun check(c: AppCategory, voices: Int, detune: Double, drive: Double, fold: Double, crush: Int, from: Double, to: Double, q: Double, click: Double) {
            val t = Motif.timbre(c)
            assertEquals("$c voices", voices, t.voices)
            assertEquals("$c detune", detune, t.detune, 0.0)
            assertEquals("$c drive", drive, t.drive, 0.0)
            assertEquals("$c fold", fold, t.fold, 0.0)
            assertEquals("$c crush", crush, t.crush)
            assertEquals("$c lpFrom", from, t.lpFrom, 0.0)
            assertEquals("$c lpTo", to, t.lpTo, 0.0)
            assertEquals("$c q", q, t.q, 0.0)
            assertEquals("$c click", click, t.click, 0.0)
        }
        check(AppCategory.VIDEO, 2, 14.0, 4.0, 0.0, 0, 2400.0, 900.0, 2.0, 0.015)
        check(AppCategory.MUSIC, 3, 10.0, 5.0, 1.3, 0, 3200.0, 1100.0, 7.0, 0.02)
        check(AppCategory.GAMES, 2, 6.0, 8.0, 0.0, 8, 7000.0, 3000.0, 1.5, 0.02)
        check(AppCategory.TOOLS, 1, 0.0, 10.0, 2.0, 0, 5000.0, 2000.0, 1.5, 0.045)
        check(AppCategory.OTHER, 2, 9.0, 6.0, 1.5, 0, 4000.0, 1500.0, 2.0, 0.02)
    }

    @Test fun voiceLayoutFollowsTheSpec() {
        for (g in golden) {
            val voices = Motif.voices(g.pkg, AppCategory.OTHER)
            val notes = voices.dropLast(1)
            val sub = voices.last()
            assertEquals(g.midi.size, notes.size)
            // note i starts at i * gap; the last is held 0.24 s or released at 0.1 s; the others last 0.11 s
            notes.forEachIndexed { i, v ->
                assertEquals(i * g.gap / 1000.0, v.at, 1e-12)
                val expectedDur = if (i == notes.lastIndex) (if (g.hold) 0.24 else 0.1) else 0.11
                assertEquals(expectedDur, v.dur, 0.0)
                assertEquals(0.026, v.gain, 1e-12)
                assertEquals(if (i == 0) 0.02 else 0.0, v.click, 0.0)
            }
            // the sub sits two octaves under the first note
            assertEquals(notes.first().f / 4, sub.f, 1e-9)
            assertEquals(0.03, sub.gain, 1e-12)
        }
    }

    @Test fun humanizingScalesPitchAndLevelButNotTheTune() {
        val plain = Motif.voices("com.plexapp.android", AppCategory.VIDEO)
        val human = Motif.voices("com.plexapp.android", AppCategory.VIDEO, Humanize(cents = 5.0, velocity = 1.1))
        val ratio = Math.pow(2.0, 5.0 / 1200)
        plain.dropLast(1).zip(human.dropLast(1)).forEach { (p, h) ->
            assertEquals(p.f * ratio, h.f, 1e-9)
            assertEquals(p.gain * 1.1, h.gain, 1e-12)
        }
    }

    @Test fun humanizeDrawsCentsThenLevelWithinTheSpecRanges() {
        val draws = doubleArrayOf(0.0, 1.0).iterator()
        val low = Humanize.random { 0.0 }
        assertEquals(-5.0, low.cents, 0.0)
        assertEquals(0.9, low.velocity, 1e-12)
        val high = Humanize.random { 1.0 }
        assertEquals(5.0, high.cents, 0.0)
        assertEquals(1.1, high.velocity, 1e-12)
        // order: the first draw is the pitch, the second the level
        val mixed = Humanize.random { if (draws.hasNext()) draws.next() else 0.5 }
        assertEquals(-5.0, mixed.cents, 0.0)
        assertEquals(1.1, mixed.velocity, 1e-12)
        assertTrue(Humanize.NONE.cents == 0.0 && Humanize.NONE.velocity == 1.0)
    }

    @Test fun playbackRateShiftsPitchByTheRightNumberOfCents() {
        assertEquals(48000, Humanize.NONE.playbackRate(48000))
        val up = Humanize(5.0, 1.0).playbackRate(48000)
        val down = Humanize(-5.0, 1.0).playbackRate(48000)
        assertEquals(48139, up) // 48000 * 2^(5/1200)
        assertEquals(47862, down)
    }
}

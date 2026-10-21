package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotifSpecsTest {

    @Test
    fun plexMotifBecomesFourNotesAndASub() {
        // Plex: D4 E4 A4 D5, gap 56 ms, hold = true (BUILD_PLAN section 8.3)
        val motif = motifFor("com.plexapp.android")
        val timbre = timbreFor(AppCategory.VIDEO)
        val specs = motifVoiceSpecs(motif, timbre, motif.seed.toLong())
        assertEquals(5, specs.size)

        for (i in 0 until 4) {
            val n = specs[i]
            assertEquals(motif.frequencies[i], n.freq, 0.0)
            val nominal = i * 0.056
            assertTrue("note $i starts at ${n.atSec}", n.atSec >= nominal && n.atSec < nominal + 0.003)
            assertEquals(if (i == 3) 0.24 else 0.11, n.dur, 0.0) // the held last note is longer
            assertEquals(0.026, n.gain, 0.0)
            assertEquals(2, n.voices)
            assertEquals(14.0, n.detune, 0.0)
            assertEquals(4.0, n.drive, 0.0)
            assertEquals(0.0, n.fold, 0.0)
            assertEquals(0.0, n.crush, 0.0)
            assertEquals(2400.0, n.lpFrom, 0.0)
            assertEquals(900.0, n.lpTo, 0.0)
            assertEquals(2.0, n.q, 0.0)
            assertEquals(if (i == 0) 0.015 else 0.0, n.click, 0.0) // click on the first note only
            assertEquals(null, n.glideTo)
        }

        val sub = specs[4]
        assertEquals(motif.frequencies[0] / 4.0, sub.freq, 1e-12)
        assertEquals(0.0, sub.atSec, 0.0)
        assertEquals(1, sub.voices)
        assertEquals(0.0, sub.detune, 0.0)
        assertEquals(0.26, sub.dur, 0.0)
        assertEquals(0.03, sub.gain, 0.0)
        assertEquals(0.0, sub.fold, 0.0)
        assertEquals(3.0, sub.drive, 0.0)
        assertEquals(400.0, sub.lpFrom, 0.0)
        assertEquals(150.0, sub.lpTo, 0.0)
        assertEquals(0.0, sub.click, 0.0)
    }

    @Test
    fun lastNoteIsShortUnlessTheMotifHolds() {
        // Netflix: 4 notes, gap 62, no hold. Tidal: 3 notes, gap 45, hold.
        val netflix = motifFor("com.netflix.ninja")
        val ns = motifVoiceSpecs(netflix, timbreFor(AppCategory.VIDEO), 1L)
        assertEquals(5, ns.size)
        assertEquals(listOf(0.11, 0.11, 0.11, 0.10), ns.take(4).map { it.dur })

        val tidal = motifFor("com.aspiro.tidal")
        val ts = motifVoiceSpecs(tidal, timbreFor(AppCategory.MUSIC), 1L)
        assertEquals(4, ts.size) // 3 notes + sub
        assertEquals(listOf(0.11, 0.11, 0.24), ts.take(3).map { it.dur })
        for (i in 0 until 3) assertEquals(3, ts[i].voices) // music timbre
        assertEquals(7.0, ts[0].q, 0.0)
        assertEquals(0.02, ts[0].click, 0.0)
    }

    @Test
    fun everyCategoryUsesItsTimbreRow() {
        val motif = motifFor("com.example.app")
        for (category in AppCategory.values()) {
            val t = timbreFor(category)
            val note = motifVoiceSpecs(motif, t, 5L)[0]
            assertEquals(t.voices, note.voices)
            assertEquals(t.detune, note.detune, 0.0)
            assertEquals(t.drive, note.drive, 0.0)
            assertEquals(t.fold, note.fold, 0.0)
            assertEquals(t.crush, note.crush, 0.0)
            assertEquals(t.lpFrom, note.lpFrom, 0.0)
            assertEquals(t.lpTo, note.lpTo, 0.0)
            assertEquals(t.q, note.q, 0.0)
            assertEquals(t.click, note.click, 0.0)
        }
    }

    @Test
    fun jitterIsBakedInFromTheSeedAndStaysInRange() {
        val motif = motifFor("com.retroarch")
        val t = timbreFor(AppCategory.GAMES)
        val a = motifVoiceSpecs(motif, t, 99L)
        val b = motifVoiceSpecs(motif, t, 99L)
        val c = motifVoiceSpecs(motif, t, 100L)
        assertEquals(a, b)
        assertNotEquals(a.map { it.atSec }, c.map { it.atSec })
        for (seed in 0L..200L) {
            val specs = motifVoiceSpecs(motif, t, seed)
            for (i in 0 until motif.frequencies.size) {
                val jitter = specs[i].atSec - i * motif.gapMs / 1000.0
                assertTrue("jitter $jitter", jitter >= 0.0 && jitter < 0.003)
            }
        }
        // across seeds the jitter really varies and covers the range
        val jitters = (0L..200L).map { motifVoiceSpecs(motif, t, it)[1].atSec - motif.gapMs / 1000.0 }
        assertTrue(jitters.max() - jitters.min() > 0.002)
    }

    @Test
    fun notesAlwaysRiseInTimeAndPitch() {
        for (pkg in listOf("a", "b.c", "com.x.y", "tv.z", "org.q.r.s")) {
            val m = motifFor(pkg)
            val specs = motifVoiceSpecs(m, timbreFor(AppCategory.OTHER), m.seed.toLong())
            for (i in 1 until m.frequencies.size) {
                assertTrue(specs[i].freq > specs[i - 1].freq)
                assertTrue(specs[i].atSec > specs[i - 1].atSec)
            }
        }
    }
}

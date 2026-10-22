package com.andrewevmiller.tvlauncher.sound.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the §8.1 'Fixed sounds' table: any drift from the plan/mockup shows up here. */
class FixedSoundsTest {
    private val d = VoiceSpec(freq = 1.0) // the mockup's defaults

    @Test
    fun defaultsMatchThePlan() {
        assertEquals(0.0, d.atSec, 0.0)
        assertNull(d.glideTo)
        assertEquals(2, d.voices)
        assertEquals(9.0, d.detune, 0.0)
        assertEquals(7000.0, d.lpFrom, 0.0)
        assertEquals(2500.0, d.lpTo, 0.0)
        assertEquals(1.5, d.q, 0.0)
        assertEquals(6.0, d.drive, 0.0)
        assertEquals(2.2, d.fold, 0.0)
        assertEquals(0.0, d.crush, 0.0)
        assertEquals(0.0, d.click, 0.0)
    }

    @Test
    fun tick() {
        for (row in 0..5) {
            val t = FixedSounds.tick(row, held = false).single()
            assertEquals(1320.0 - 60.0 * row, t.freq, 0.0)
            assertEquals(0.03, t.dur, 0.0)
            assertEquals(0.02, t.gain, 0.0)
            assertEquals(1, t.voices)
            assertEquals(3.0, t.fold, 0.0)
            assertEquals(8.0, t.drive, 0.0)
            assertEquals(9000.0, t.lpFrom, 0.0)
            assertEquals(3000.0, t.lpTo, 0.0)
            assertEquals(0.03, t.click, 0.0)
            assertEquals(1.5, t.q, 0.0)
        }
        val held = FixedSounds.tick(2, held = true).single()
        assertEquals(1200.0, held.freq, 0.0)
        assertEquals(0.02, held.dur, 0.0)
        assertEquals(0.012, held.gain, 0.0)
        assertEquals(0.01, held.click, 0.0)
        assertEquals(1, held.voices)
        assertEquals(3.0, held.fold, 0.0)
        assertEquals(8.0, held.drive, 0.0)
    }

    @Test
    fun edge() {
        val e = FixedSounds.edge().single()
        assertEquals(70.0, e.freq, 0.0)
        assertEquals(50.0, e.glideTo!!, 0.0)
        assertEquals(0.13, e.dur, 0.0)
        assertEquals(0.06, e.gain, 0.0)
        assertEquals(20.0, e.detune, 0.0)
        assertEquals(3.5, e.fold, 0.0)
        assertEquals(10.0, e.drive, 0.0)
        assertEquals(12.0, e.crush, 0.0)
        assertEquals(1800.0, e.lpFrom, 0.0)
        assertEquals(300.0, e.lpTo, 0.0)
        assertEquals(0.02, e.click, 0.0)
        assertEquals(2, e.voices)
    }

    @Test
    fun selectAndBack() {
        val s = FixedSounds.select()
        assertEquals(2, s.size)
        assertEquals(listOf(440.0, 660.0), s.map { it.freq })
        assertEquals(listOf(0.0, 0.05), s.map { it.atSec })
        assertEquals(listOf(0.08, 0.10), s.map { it.dur })
        assertEquals(listOf(0.028, 0.026), s.map { it.gain })
        assertEquals(listOf(0.025, 0.0), s.map { it.click })
        for (v in s) {
            assertEquals(2.5, v.fold, 0.0)
            assertEquals(7.0, v.drive, 0.0)
        }
        val b = FixedSounds.back()
        assertEquals(2, b.size)
        assertEquals(listOf(660.0, 440.0), b.map { it.freq })
        assertEquals(listOf(0.0, 0.045), b.map { it.atSec })
        assertEquals(listOf(0.07, 0.10), b.map { it.dur })
        assertEquals(listOf(0.026, 0.028), b.map { it.gain })
        assertEquals(listOf(0.025, 0.0), b.map { it.click })
        for (v in b) {
            assertEquals(2.5, v.fold, 0.0)
            assertEquals(7.0, v.drive, 0.0)
        }
    }

    @Test
    fun launchFallback() {
        val l = FixedSounds.launchFallback()
        assertEquals(5, l.size)
        val freqs = doubleArrayOf(261.6, 329.6, 392.0, 523.3)
        for (i in 0 until 4) {
            val n = l[i]
            assertEquals(freqs[i], n.freq, 0.0)
            assertEquals(i * 0.045, n.atSec, 1e-12)
            assertEquals(0.28 - i * 0.03, n.dur, 1e-12)
            assertEquals(0.024, n.gain, 0.0)
            assertEquals(1.6 + i * 0.6, n.fold, 1e-12)
            assertEquals(7.0, n.drive, 0.0)
            assertEquals(2500.0 + i * 1200.0, n.lpFrom, 0.0)
            assertEquals(6000.0, n.lpTo, 0.0)
            assertEquals(3.0, n.q, 0.0)
            assertEquals(if (i == 0) 0.035 else 0.0, n.click, 0.0)
            assertEquals(2, n.voices)
        }
        val sub = l[4]
        assertEquals(65.4, sub.freq, 0.0)
        assertEquals(0.0, sub.atSec, 0.0)
        assertEquals(0.3, sub.dur, 0.0)
        assertEquals(0.035, sub.gain, 0.0)
        assertEquals(1, sub.voices)
        assertEquals(1.2, sub.fold, 0.0)
        assertEquals(4.0, sub.drive, 0.0)
        assertEquals(600.0, sub.lpFrom, 0.0)
        assertEquals(200.0, sub.lpTo, 0.0)
    }

    @Test
    fun sleepAndWake() {
        val s = FixedSounds.sleep().single()
        assertEquals(110.0, s.freq, 0.0)
        assertEquals(165.0, s.glideTo!!, 0.0)
        assertEquals(1.7, s.dur, 0.0)
        assertEquals(0.024, s.gain, 0.0)
        assertEquals(25.0, s.detune, 0.0)
        assertEquals(1.5, s.fold, 0.0)
        assertEquals(5.0, s.drive, 0.0)
        assertEquals(600.0, s.lpFrom, 0.0)
        assertEquals(4000.0, s.lpTo, 0.0)
        assertEquals(4.0, s.q, 0.0)
        assertEquals(0.0, s.click, 0.0)

        val w = FixedSounds.wake().single()
        assertEquals(220.0, w.freq, 0.0)
        assertEquals(330.0, w.glideTo!!, 0.0)
        assertEquals(0.26, w.dur, 0.0)
        assertEquals(0.028, w.gain, 0.0)
        assertEquals(3.0, w.fold, 0.0)
        assertEquals(8.0, w.drive, 0.0)
        assertEquals(1500.0, w.lpFrom, 0.0)
        assertEquals(7000.0, w.lpTo, 0.0)
        assertEquals(0.02, w.click, 0.0)
        assertEquals(1.5, w.q, 0.0)
    }
}

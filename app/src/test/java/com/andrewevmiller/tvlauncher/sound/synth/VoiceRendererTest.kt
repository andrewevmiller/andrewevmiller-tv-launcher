package com.andrewevmiller.tvlauncher.sound.synth

import com.andrewevmiller.tvlauncher.sound.Waveform
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRendererTest {
    private val sr = SAMPLE_RATE

    private val sounds: List<Pair<String, List<VoiceSpec>>> = listOf(
        "tick(0)" to FixedSounds.tick(0, false),
        "tick(4,held)" to FixedSounds.tick(4, true),
        "edge" to FixedSounds.edge(),
        "select" to FixedSounds.select(),
        "back" to FixedSounds.back(),
        "launch" to FixedSounds.launchFallback(),
        "sleep" to FixedSounds.sleep(),
        "wake" to FixedSounds.wake(),
    )

    private val ceilingLinear = 10.0.pow(-1.0 / 20.0)

    /** Single plain voice: near-identity shaper, wide-open low-pass, no click. */
    private fun plain(freq: Double, dur: Double, gain: Double = 0.03, glideTo: Double? = null, at: Double = 0.0) = VoiceSpec(
        freq = freq, glideTo = glideTo, atSec = at, dur = dur, gain = gain, voices = 1, drive = 1e-4, fold = 0.0,
        lpFrom = 20000.0, lpTo = 20000.0, q = 0.0, click = 0.0,
    )

    @Test
    fun everyFixedSoundRendersCleanlyInEveryWaveformUnderTheCeiling() {
        val needed = ArrayList<String>()
        for (wf in Waveform.values()) {
            val r = VoiceRenderer(wf)
            for ((name, specs) in sounds) {
                val info = RenderInfo()
                val out = r.renderWithInfo(specs, info)
                assertTrue("$wf $name finite", TestDsp.allFinite(out))
                val peak = TestDsp.peak(out)
                assertTrue("$wf $name peak $peak", peak <= ceilingLinear)
                assertTrue("$wf $name not silent (rms ${TestDsp.rms(out)})", TestDsp.rms(out) > 1e-5)
                if (info.ceilingApplied) needed += "$wf/$name (peak ${"%.3f".format(info.peakBeforeCeiling)})"
            }
        }
        println("sounds that needed the -1 dBFS soft ceiling: " + if (needed.isEmpty()) "none" else needed.joinToString())
    }

    @Test
    fun peakLevelsPerSoundAndWaveformAreReported() {
        val sb = StringBuilder("peak dBFS per sound x waveform (rows: sound)\n")
        sb.append("%-14s".format("")).append(Waveform.values().joinToString("") { "%-12s".format(it.name) }).append('\n')
        val renderers = Waveform.values().map { VoiceRenderer(it) }
        for ((name, specs) in sounds) {
            sb.append("%-14s".format(name))
            for (r in renderers) sb.append("%-12s".format("%.1f".format(20 * log10(TestDsp.peak(r.render(specs))))))
            sb.append('\n')
        }
        println(sb)
    }

    @Test
    fun sameSeedIsIdenticalAndDifferentSeedsDifferOnlySlightly() {
        for (wf in Waveform.values()) {
            for ((name, specs) in sounds.filter { it.first in setOf("select", "launch", "edge") }) {
                val a = VoiceRenderer(wf, seed = 5L).render(specs)
                val b = VoiceRenderer(wf, seed = 5L).render(specs)
                val c = VoiceRenderer(wf, seed = 6L).render(specs)
                assertTrue("$wf $name same seed", a.contentEquals(b))
                assertEquals(a.size, c.size)
                assertFalse("$wf $name different seed", a.contentEquals(c))
                val dRms = 20 * log10(TestDsp.rms(c) / TestDsp.rms(a))
                val noise = wf == Waveform.WHITE_NOISE || wf == Waveform.PINK_NOISE
                // the seed moves voice phases (jitter), noise read offsets and the click burst: loudness barely changes
                // for tonal voices; a narrow noise band is itself random, so its level wanders more
                assertTrue("$wf $name seed changes level by ${"%.2f".format(dRms)} dB", abs(dRms) < if (noise) 6.0 else 2.5)
            }
        }
        // a renderer can be reused: same call, same samples
        val r = VoiceRenderer(Waveform.SAW, seed = 9L)
        assertTrue(r.render(FixedSounds.select()).contentEquals(r.render(FixedSounds.select())))
    }

    @Test
    fun selectLoudnessAcrossWaveformsIsWithinEightDb() {
        val specs = FixedSounds.select()
        val levels = Waveform.values().associateWith { TestDsp.rmsDb(VoiceRenderer(it).render(specs)) }
        val mean = levels.values.average()
        val sb = StringBuilder("select RMS per waveform (dBFS, whole buffer incl. silence; mean ${"%.1f".format(mean)}):\n")
        for ((wf, db) in levels) sb.append("  %-12s %7.2f dB  (%+.1f vs mean)\n".format(wf.name, db, db - mean))
        println(sb)
        for ((wf, db) in levels) assertEquals("$wf", mean, db, 8.0)
    }

    @Test
    fun renderedLengthCoversEverySpecPlusTheMasterTail() {
        val r = VoiceRenderer(Waveform.SAW)
        val out = r.render(FixedSounds.select())
        val tail = ceil(VoiceRenderer.MASTER_TAIL_SECONDS * sr).toInt()
        // select: the second note starts at 50 ms and runs 0.10 s + 30 ms
        assertEquals(2400 + 6240 + tail, out.size)
        // the 1.7 s sleep sound: 1.73 s
        assertEquals(83040 + tail, r.render(FixedSounds.sleep()).size)
        assertEquals(0, r.render(emptyList()).size)
        // a spec that cannot sound is skipped rather than crashing
        assertEquals(0, r.render(listOf(VoiceSpec(freq = 440.0, dur = 0.0))).size)
    }

    @Test
    fun envelopeRisesOverThreeMillisecondsThenDecaysExponentially() {
        val gain = 0.05
        val dur = 0.1
        val out = VoiceRenderer(Waveform.SINE).render(listOf(plain(440.0, dur, gain)))
        val master = 0.8 * DynamicsCompressor(sr).makeupGain // compressor is below threshold: just make-up gain
        fun windowPeak(tSec: Double, halfMs: Double = 1.2): Double {
            val lo = ((tSec - halfMs / 1000) * sr).toInt().coerceAtLeast(0)
            val hi = ((tSec + halfMs / 1000) * sr).toInt()
            var p = 0.0
            for (i in lo until hi) p = maxOf(p, abs(out[i].toDouble()))
            return p
        }
        fun expected(t: Double): Double = when {
            t < 0.003 -> 0.0001 * (gain / 0.0001).pow(t / 0.003)
            t < dur -> gain * (0.0001 / gain).pow((t - 0.003) / (dur - 0.003))
            else -> 0.0001
        } * master
        assertEquals("peak", gain * master, TestDsp.peak(out), 0.1 * gain * master)
        for (t in doubleArrayOf(0.02, 0.04, 0.06)) {
            val got = windowPeak(t)
            val exp = expected(t)
            assertEquals("env at $t s", exp, got, 0.2 * exp) // a half-cycle window of an exponential: ~10-20% either way
        }
        // after `dur` only the 0.0001 floor leaks until the oscillator stops at dur + 30 ms
        assertTrue(windowPeak(dur + 0.01) < 3e-4)
        assertTrue(windowPeak(dur + 0.04) < 1e-5) // beyond the stop time: silence
    }

    @Test
    fun notesStartAtTheirOffsets() {
        val out = VoiceRenderer(Waveform.SINE).render(listOf(plain(440.0, 0.05, 0.05), plain(880.0, 0.05, 0.05, at = 0.2)))
        var firstLate = out.size
        for (i in out.indices) if (abs(out[i]) > 1e-3) { firstLate = i; break }
        assertTrue(firstLate < 0.01 * sr)
        // quiet gap between the notes, and the second note begins at 0.2 s
        var gapPeak = 0.0
        for (i in (0.1 * sr).toInt() until (0.19 * sr).toInt()) gapPeak = maxOf(gapPeak, abs(out[i].toDouble()))
        assertTrue(gapPeak < 1e-4)
        var secondStart = -1
        for (i in (0.19 * sr).toInt() until out.size) if (abs(out[i]) > 1e-3) { secondStart = i; break }
        assertEquals(0.2 * sr + 0.002 * sr, secondStart.toDouble(), 0.002 * sr)
    }

    @Test
    fun clickIsABrightBurstOnTheAttackThatDiesQuickly() {
        val r = VoiceRenderer(Waveform.SAW)
        val base = plain(440.0, 0.1, 0.0002)
        val clicked = base.copy(click = 0.05)
        val a = r.render(listOf(base))
        val b = r.render(listOf(clicked))
        assertEquals(a.size, b.size)
        val diff = FloatArray(a.size) { b[it] - a[it] }
        val early = TestDsp.peak(diff.copyOfRange(0, (0.012 * sr).toInt()))
        val late = TestDsp.peak(diff.copyOfRange((0.03 * sr).toInt(), diff.size))
        println("click: early peak ${"%.4f".format(early)}, after 30 ms ${"%.6f".format(late)}")
        assertTrue("click peak $early", early in 0.01..0.2)
        assertTrue("click gone by 30 ms: $late", late < 1e-3)
        // zero click leaves the render alone
        assertTrue(a.contentEquals(r.render(listOf(base.copy(click = 0.0)))))
    }

    @Test
    fun noiseVoicesSingTheNoteAndFollowTheGlide() {
        for (wf in listOf(Waveform.WHITE_NOISE, Waveform.PINK_NOISE)) {
            val r = VoiceRenderer(wf, seed = 3L)
            val spec = plain(440.0, 0.5, 0.03)
            val out = r.render(listOf(spec))
            val x = DoubleArray(16384) { out[it + 1000].toDouble() }
            val spectrum = TestDsp.welch(x, 4096)
            var best = 1
            for (k in 1 until spectrum.size) if (spectrum[k] > spectrum[best]) best = k
            val peakHz = best * sr / 4096.0
            assertEquals("$wf dominant frequency", 440.0, peakHz, 440.0 * 0.06)
            assertTrue(TestDsp.allFinite(out))
        }
    }

    @Test
    fun glideSpecsGlideInTheRenderedAudio() {
        val out = VoiceRenderer(Waveform.SINE).render(listOf(plain(200.0, 0.5, 0.05, glideTo = 400.0)))
        val tail = DoubleArray(out.size) { out[it].toDouble() }
        // 0.42-0.50 s of a 0.5 s octave glide: mean log-frequency is 200 * 2^0.92 = 378 Hz
        val seg = tail.copyOfRange((0.42 * sr).toInt(), (0.5 * sr).toInt())
        assertEquals(378.4, TestDsp.zeroCrossingFrequency(seg, sr), 6.0)
        val early = tail.copyOfRange((0.02 * sr).toInt(), (0.08 * sr).toInt())
        assertTrue(TestDsp.zeroCrossingFrequency(early, sr) < 240.0)
    }

    @Test
    fun jitterStaggersTheVoicesAndAllowsSeededVariation() {
        val spec = plain(440.0, 0.08, 0.05).copy(voices = 3, detune = 12.0)
        val a = VoiceRenderer(Waveform.SQUARE, seed = 1L).render(listOf(spec))
        val b = VoiceRenderer(Waveform.SQUARE, seed = 2L).render(listOf(spec))
        assertFalse(a.contentEquals(b))
        assertTrue(TestDsp.allFinite(a) && TestDsp.allFinite(b))
    }

    @Test
    fun supersawIgnoresVoiceCountAndRespectsAMinimumDetune() {
        val one = plain(440.0, 0.1, 0.03).copy(voices = 1, detune = 0.0)
        val saw = VoiceRenderer(Waveform.SAW, seed = 4L).render(listOf(one))
        val sup = VoiceRenderer(Waveform.SUPERSAW, seed = 4L).render(listOf(one))
        assertFalse(saw.contentEquals(sup))
        // seven voices at 1/sqrt(7) and a 22-cent spread: not simply louder than one voice
        val ratio = TestDsp.rms(sup) / TestDsp.rms(saw)
        assertTrue("supersaw/saw rms ratio $ratio", ratio in 0.4..1.6)
    }

    @Test
    fun renderingAllFixedSoundsInAllWaveformsIsFast() {
        var samples = 0L
        fun pass(): Long {
            samples = 0L
            val t0 = System.nanoTime()
            for (wf in Waveform.values()) {
                val r = VoiceRenderer(wf)
                for ((_, specs) in sounds) samples += r.render(specs).size
            }
            return (System.nanoTime() - t0) / 1_000_000
        }
        val cold = pass()
        val warm = pass()
        println(
            "render time, ${sounds.size} sounds x ${Waveform.values().size} waveforms (${samples / sr} s of audio): " +
                "first pass $cold ms (JIT cold), second pass $warm ms",
        )
        assertTrue(warm < 20_000)
    }
}

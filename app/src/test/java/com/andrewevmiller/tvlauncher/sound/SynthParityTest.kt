package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** The optimized [Synth] against the original renderer kept in [ReferenceSynth], on the same random draws. */
class SynthParityTest {
    private fun seeded(seed: Int): () -> Double {
        val r = Random(seed)
        return { r.nextDouble() }
    }

    private class Case(val name: String, val voices: List<Voice>, val seconds: Double, val waveform: Waveform)

    private val packages = listOf("com.google.android.youtube.tv", "com.spotify.tv.android", "com.retroarch", "com.andrewevmiller.analyzer")

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        for (wf in Waveform.entries) {
            for (spec in FixedSounds.all) out += Case("${spec.name}/$wf", spec.voices, spec.seconds, wf)
            for (cat in AppCategory.entries) for (pkg in packages) {
                out += Case("$pkg/$cat/$wf", Motif.voices(pkg, cat, Humanize(cents = 2.5, velocity = 1.05)), 0.6, wf)
            }
        }
        return out
    }

    private class Error(val maxLsb: Int, val relRms: Double)

    private fun compare(reference: ShortArray, actual: ShortArray): Error {
        assertEquals(reference.size, actual.size)
        var max = 0
        var errSq = 0.0
        var refSq = 0.0
        for (i in reference.indices) {
            val d = abs(reference[i] - actual[i])
            if (d > max) max = d
            errSq += d.toDouble() * d
            refSq += reference[i].toDouble() * reference[i]
        }
        return Error(max, if (refSq == 0.0) 0.0 else sqrt(errSq / refSq))
    }

    private fun worst(controlPeriod: Int): Pair<Error, String> {
        var worstMax = Error(0, 0.0); var worstMaxCase = ""
        var worstRel = 0.0; var worstRelCase = ""
        for ((idx, c) in cases().withIndex()) {
            val ref = ReferenceSynth.render(c.voices, c.seconds, c.waveform, seeded(idx))
            val act = Synth.render(c.voices, c.seconds, c.waveform, seeded(idx), controlPeriod)
            val e = compare(ref, act)
            if (e.maxLsb > worstMax.maxLsb) { worstMax = e; worstMaxCase = c.name }
            if (e.relRms > worstRel) { worstRel = e.relRms; worstRelCase = c.name }
        }
        println("controlPeriod=$controlPeriod: worst peak error ${worstMax.maxLsb} LSB of 32000 (${worstMaxCase}); worst relative RMS error $worstRel ($worstRelCase)")
        return Error(worstMax.maxLsb, worstRel) to "$worstMaxCase / $worstRelCase"
    }

    @Test fun withPerSampleFilterUpdatesTheOptimizedRendererMatchesTheOriginal() {
        val (e, where) = worst(controlPeriod = 1)
        // Measured: identical at 16-bit output. Allow a stray rounding flip, nothing more.
        assertTrue("hoisting changed the sound: peak error ${e.maxLsb} LSB at $where", e.maxLsb <= 2)
        assertTrue("relative RMS error ${e.relRms} at $where", e.relRms < 1e-4)
    }

    @Test fun withTheShippedFilterUpdateRateTheDifferenceIsFarBelowAudible() {
        val (e, where) = worst(controlPeriod = Synth.CONTROL_PERIOD)
        // Measured at period 4: 13 LSB peak (-68 dB), 0.25% relative RMS; the worst cases are the short bright ticks.
        assertTrue("peak error ${e.maxLsb} LSB at $where", e.maxLsb <= 30)
        assertTrue("relative RMS error ${e.relRms} at $where", e.relRms < 0.005)
    }

    @Test fun rendersAreRepeatableOnTheSameDraws() {
        val voices = Motif.voices("com.plexapp.android", AppCategory.MUSIC)
        val a = Synth.render(voices, 0.6, Waveform.SUPERSAW, seeded(7))
        val b = Synth.render(voices, 0.6, Waveform.SUPERSAW, seeded(7))
        assertTrue(a.contentEquals(b))
    }

    @Test fun outputIsAudibleAndWithinRange() {
        for (c in cases()) {
            val pcm = Synth.render(c.voices, c.seconds, c.waveform, seeded(1))
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("${c.name} is silent", peak > 500)
            assertTrue("${c.name} clips: $peak", peak <= 32000)
        }
    }

    @Test fun wavHasAValidHeader() {
        val pcm = ShortArray(100) { it.toShort() }
        val wav = Synth.wav(pcm)
        assertEquals(44 + 200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals("data", String(wav, 36, 4))
    }

    @Test fun speedComparedWithTheOriginal() {
        val voices = Motif.voices("com.spotify.tv.android", AppCategory.MUSIC)
        fun time(render: () -> ShortArray): Double {
            repeat(10) { render() } // warm up the JIT
            val runs = (0 until 15).map { val t0 = System.nanoTime(); render(); (System.nanoTime() - t0) / 1e6 }.sorted()
            return runs[runs.size / 2]
        }
        val original = time { ReferenceSynth.render(voices, 0.6, Waveform.SAW, seeded(3)) }
        val optimized = time { Synth.render(voices, 0.6, Waveform.SAW, seeded(3)) }
        val supersawOriginal = time { ReferenceSynth.render(voices, 0.6, Waveform.SUPERSAW, seeded(3)) }
        val supersawOptimized = time { Synth.render(voices, 0.6, Waveform.SUPERSAW, seeded(3)) }
        println("motif render, median of 15 (JVM, warm): original %.1f ms, optimized %.1f ms (%.1fx); supersaw original %.1f ms, optimized %.1f ms (%.1fx)"
            .format(original, optimized, original / optimized, supersawOriginal, supersawOptimized, supersawOriginal / supersawOptimized))
        assertTrue("optimized is only ${original / optimized}x faster", original / optimized > 1.1)
    }
}

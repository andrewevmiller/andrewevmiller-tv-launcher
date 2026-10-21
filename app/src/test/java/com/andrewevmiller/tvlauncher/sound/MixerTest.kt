package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.sound.synth.FixedSounds
import com.andrewevmiller.tvlauncher.sound.synth.SAMPLE_RATE
import com.andrewevmiller.tvlauncher.sound.synth.TestDsp
import com.andrewevmiller.tvlauncher.sound.synth.VoiceRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerTest {
    private val sr = SAMPLE_RATE

    private fun sine(freq: Double, seconds: Double, amp: Float = 0.5f): Clip =
        Clip(FloatArray((seconds * sr).toInt()) { (amp * sin(2.0 * PI * freq * it / sr)).toFloat() })

    /** Pulls [total] samples from the mixer in blocks of the given sizes (cycled), like the audio thread would. */
    private fun pull(mixer: Mixer, total: Int, blockSizes: IntArray = intArrayOf(64)): FloatArray {
        val out = FloatArray(total)
        var done = 0
        var k = 0
        while (done < total) {
            val n = minOf(blockSizes[k++ % blockSizes.size], total - done)
            val block = FloatArray(n)
            mixer.mix(block, n)
            System.arraycopy(block, 0, out, done, n)
            done += n
        }
        return out
    }

    @Test
    fun singleVoiceReproducesTheClipAcrossBlockBoundaries() {
        val clip = Clip(FloatArray(1000) { ((it * 37) % 101 - 50) / 120f })
        val mixer = Mixer()
        mixer.add(clip)
        assertTrue(mixer.hasActiveVoices())
        val out = pull(mixer, 1300, intArrayOf(64, 100, 7, 256, 1))
        for (i in 0 until 1000) assertEquals("sample $i", clip.samples[i], out[i], 1e-6f)
        for (i in 1000 until 1300) assertEquals(0f, out[i], 0f)
        assertFalse(mixer.hasActiveVoices())
        assertEquals(0, mixer.activeVoices)
    }

    @Test
    fun overlappingVoicesSum() {
        val a = Clip(FloatArray(500) { 0.2f })
        val b = Clip(FloatArray(500) { -0.05f + it * 0.0002f })
        val mixer = Mixer()
        mixer.add(a)
        mixer.add(b, delaySamples = 300)
        val out = pull(mixer, 900, intArrayOf(128, 33))
        for (i in 0 until 300) assertEquals(0.2f, out[i], 1e-6f)
        for (i in 300 until 500) assertEquals(0.2f + b.samples[i - 300], out[i], 1e-6f)
        for (i in 500 until 800) assertEquals(b.samples[i - 300], out[i], 1e-6f)
        for (i in 800 until 900) assertEquals(0f, out[i], 0f)
    }

    @Test
    fun gainAndMasterGainScaleTheVoice() {
        val clip = Clip(FloatArray(200) { 0.1f })
        val mixer = Mixer()
        mixer.add(clip, gain = 0.5f)
        mixer.masterGain = 3f
        val out = pull(mixer, 200)
        for (v in out) assertEquals(0.15f, v, 1e-6f)
    }

    @Test
    fun playbackRateShiftsPitchByTheRequestedCents() {
        val clip = sine(440.0, 2.0)
        var worst = 0.0
        for (cents in doubleArrayOf(-5.0, -2.5, 0.0, 2.5, 5.0, 100.0)) {
            val mixer = Mixer()
            mixer.add(clip, rate = rateForCents(cents))
            val out = pull(mixer, (1.8 * sr).toInt(), intArrayOf(256))
            val f = TestDsp.zeroCrossingFrequency(DoubleArray(out.size) { out[it].toDouble() }, sr)
            val err = TestDsp.cents(f, 440.0) - cents
            worst = maxOf(worst, abs(err))
            assertEquals("cents $cents", 0.0, err, 0.05)
        }
        println("mixer pitch accuracy: worst error ${"%.6f".format(worst)} cents over -5..+5 and +100 cents")
        // 5 cents is a 0.29% rate change; a +-5 cent launch also shortens/lengthens the clip by that much
        assertEquals(1.00289229, rateForCents(5.0), 1e-8)
        assertEquals(1.0, rateForCents(0.0), 0.0)
        assertEquals(2.0, rateForCents(1200.0), 1e-12)
    }

    @Test
    fun rateChangesTheLengthByTheSameRatio() {
        val clip = Clip(FloatArray(48000) { 0.3f })
        val mixer = Mixer()
        mixer.add(clip, rate = 2.0)
        val out = pull(mixer, 30000)
        var last = -1
        for (i in out.indices) if (out[i] != 0f) last = i
        assertEquals(23999.0, last.toDouble(), 2.0)
    }

    @Test
    fun delaysAreSampleAccurateAcrossBlockBoundaries() {
        val impulse = Clip(floatArrayOf(0.5f, 0.25f, 0.125f))
        for (delay in intArrayOf(0, 1, 7, 63, 64, 65, 127, 128, 129, 500, 1000)) {
            for (block in intArrayOf(64, 100, 1)) {
                val mixer = Mixer()
                mixer.add(impulse, delaySamples = delay)
                val out = pull(mixer, delay + 200, intArrayOf(block))
                var first = -1
                for (i in out.indices) if (out[i] != 0f) {
                    first = i
                    break
                }
                assertEquals("delay $delay block $block", delay, first)
                assertEquals(0.5f, out[delay], 0f)
                assertEquals(0.25f, out[delay + 1], 0f)
                assertEquals(0.125f, out[delay + 2], 0f)
            }
        }
        // negative delays count as zero
        val mixer = Mixer()
        mixer.add(impulse, delaySamples = -50)
        assertEquals(0.5f, pull(mixer, 8)[0], 0f)
    }

    @Test
    fun delayedVoiceKeepsTheMixerActiveUntilItHasPlayed() {
        val mixer = Mixer()
        mixer.add(Clip(floatArrayOf(0.1f, 0.1f)), delaySamples = 1000)
        assertTrue(mixer.hasActiveVoices())
        pull(mixer, 900)
        assertTrue(mixer.hasActiveVoices())
        pull(mixer, 200)
        assertFalse(mixer.hasActiveVoices())
    }

    @Test
    fun limiterIsTransparentBelowTheKneeAndBoundedAbove() {
        for (x in floatArrayOf(0f, 0.1f, -0.5f, 0.79f, -0.8f)) assertEquals(x, softLimit(x), 0f)
        assertEquals(0f, softLimit(Float.NaN), 0f)
        var prev = softLimit(0f)
        var x = 0f
        while (x < 50f) {
            val y = softLimit(x)
            assertTrue("monotonic at $x", y >= prev - 1e-7f)
            assertTrue("bounded at $x", y <= 1f)
            assertEquals("odd symmetry", -y, softLimit(-x), 0f)
            prev = y
            x += 0.01f
        }
        assertEquals(1f, softLimit(1000f), 1e-6f)
        assertEquals(-1f, softLimit(Float.NEGATIVE_INFINITY), 1e-6f)
        // continuous at the knee
        assertEquals(softLimit(LIMITER_KNEE), softLimit(LIMITER_KNEE + 1e-5f), 1e-4f)
    }

    @Test
    fun manyLoudOverlapsNeverExceedFullScale() {
        val mixer = Mixer()
        val loud = Clip(FloatArray(2000) { if ((it / 20) % 2 == 0) 1f else -1f })
        repeat(16) { mixer.add(loud, gain = 2f) }
        val out = pull(mixer, 2100, intArrayOf(256))
        var peak = 0f
        for (v in out) {
            assertFalse(v.isNaN())
            assertTrue(abs(v) <= 1f)
            peak = maxOf(peak, abs(v))
        }
        assertTrue(peak > 0.99f)
    }

    @Test
    fun nanInputNeverReachesTheOutput() {
        val mixer = Mixer()
        mixer.add(Clip(floatArrayOf(0.1f, Float.NaN, 0.1f, Float.POSITIVE_INFINITY)), gain = Float.NaN)
        mixer.add(Clip(floatArrayOf(0.1f, Float.NaN, 0.1f)), rate = Double.NaN)
        mixer.add(Clip(floatArrayOf(0.2f, 0.2f)), rate = -3.0, gain = 1f)
        mixer.add(Clip(FloatArray(0)))
        val out = pull(mixer, 16)
        for (v in out) assertTrue(!v.isNaN() && !v.isInfinite() && abs(v) <= 1f)
    }

    @Test
    fun theOldestVoiceIsStolenWhenAllSlotsAreBusy() {
        val mixer = Mixer(maxVoices = 4)
        for (i in 1..4) mixer.add(Clip(FloatArray(100) { 0.01f * i }))
        assertEquals(4, mixer.activeVoices)
        mixer.add(Clip(FloatArray(100) { 0.05f })) // steals voice 1 (0.01)
        assertEquals(4, mixer.activeVoices)
        val out = pull(mixer, 10)
        assertEquals(0.05f + 0.02f + 0.03f + 0.04f, out[0], 1e-6f)
        for (i in 0 until 40) mixer.add(Clip(FloatArray(10) { 0.001f }))
        assertEquals(4, mixer.activeVoices)
    }

    @Test
    fun clearDropsEverything() {
        val mixer = Mixer()
        mixer.add(Clip(FloatArray(100) { 0.1f }), delaySamples = 50)
        mixer.add(Clip(FloatArray(100) { 0.1f }))
        mixer.clear()
        assertFalse(mixer.hasActiveVoices())
        assertEquals(0f, pull(mixer, 64).maxOrNull() ?: 0f, 0f)
    }

    @Test
    fun renderedSoundsEndQuietlyAndTheMixerEndsThemWithoutAStep() {
        val renderer = VoiceRenderer(Waveform.SAW)
        val specLists = listOf(FixedSounds.select(), FixedSounds.edge(), FixedSounds.tick(0, false), FixedSounds.sleep())
        for (specs in specLists) {
            val clip = Clip(renderer.render(specs))
            val tail = clip.samples.copyOfRange(clip.size - 16, clip.size)
            assertTrue("clip ends at ${TestDsp.peak(tail)}", TestDsp.peak(tail) < 1e-4)
            val mixer = Mixer()
            mixer.add(clip, rate = rateForCents(5.0), gain = 1.1f)
            val out = pull(mixer, clip.size + 100, intArrayOf(64))
            var maxStep = 0f
            for (i in 1 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
            // the biggest sample-to-sample step of the whole sound is nowhere near its end
            var lastStep = 0f
            for (i in clip.size - 200 until clip.size + 50) lastStep = maxOf(lastStep, abs(out[i] - out[i - 1]))
            assertTrue("end step $lastStep", lastStep < 1e-3f)
            assertTrue(maxStep > lastStep)
        }
    }

    @Test
    fun mixAndAddDoNotAllocate() {
        val bean = try {
            java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        } catch (t: Throwable) {
            null
        }
        org.junit.Assume.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val mixer = Mixer()
        val clip = sine(440.0, 0.5)
        val block = FloatArray(256)
        repeat(2000) { // warm up so the JIT has compiled and no lazy init remains
            mixer.add(clip, 1.002, 0.9f, 10)
            mixer.mix(block)
        }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(20000) {
            mixer.add(clip, 1.002, 0.9f, 10)
            mixer.mix(block)
        }
        val used = bean.getThreadAllocatedBytes(id) - before
        println("allocation over 20000 add+mix calls: $used bytes")
        assertTrue("allocated $used bytes", used < 65536) // a single 16-byte object per call would be 320 kB
    }
}

package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.sound.synth.SAMPLE_RATE
import com.andrewevmiller.tvlauncher.sound.synth.TestDsp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCacheTest {

    private fun forty(): List<MotifTarget> = List(40) { i ->
        MotifTarget("com.example.app$i", AppCategory.values()[i % AppCategory.values().size])
    }

    @Test
    fun fixedSetHasEveryClipForTheWaveform() {
        val cache = SoundCache()
        assertNull(cache.active())
        val fixed = cache.renderFixed(Waveform.SAW)
        assertEquals(Waveform.SAW, fixed.waveform)
        assertSame(fixed, cache.active()!!.fixed)
        val all = ArrayList<Clip>()
        for (row in 0 until FixedClips.TICK_ROWS) {
            all += fixed.tick(row, TickKind.NORMAL)
            all += fixed.tick(row, TickKind.HELD)
        }
        all += listOf(fixed.edge, fixed.select, fixed.back, fixed.launchFallback, fixed.sleep, fixed.wake, fixed.windDown)
        assertEquals(24 + 7, all.size)
        for (clip in all) {
            assertTrue(clip.size > 0)
            assertTrue(TestDsp.allFinite(clip.samples))
            assertTrue(TestDsp.peak(clip.samples) > 1e-4)
            assertTrue(TestDsp.peak(clip.samples) <= 0.891)
        }
        // held ticks are shorter than normal ones, and each row has its own pitch (own clip)
        assertTrue(fixed.tick(0, TickKind.HELD).size < fixed.tick(0, TickKind.NORMAL).size)
        assertNotEquals(fixed.tick(0, TickKind.NORMAL), fixed.tick(1, TickKind.NORMAL))
        // rows outside 0..11 clamp instead of failing
        assertSame(fixed.tick(0, TickKind.NORMAL), fixed.tick(-4, TickKind.NORMAL))
        assertSame(fixed.tick(11, TickKind.HELD), fixed.tick(99, TickKind.HELD))
        // the sleep sound is the long one: 1.7 s + 30 ms + tail
        assertTrue(fixed.sleep.size > 1.7 * SAMPLE_RATE)
        // windDown is longer than sleep (2.6 s + 30 ms + tail) and well inside 2.0..3.2 s, and not the sleep clip
        assertTrue(fixed.windDown.size > 2.6 * SAMPLE_RATE)
        assertTrue("windDown ${fixed.windDown.size.toDouble() / SAMPLE_RATE} s", fixed.windDown.size in (2.0 * SAMPLE_RATE).toInt()..(3.2 * SAMPLE_RATE).toInt())
        assertFalse(fixed.windDown.samples.contentEquals(fixed.sleep.samples))
        // quieter than the voiced select (the exact bound is asserted per waveform in VoiceRendererTest)
        assertTrue(TestDsp.peak(fixed.windDown.samples) < 0.65 * TestDsp.peak(fixed.select.samples))
    }

    private fun assertNotEquals(a: Clip, b: Clip) = assertFalse(a.samples.contentEquals(b.samples))

    @Test
    fun rendersAreDeterministicPerWaveform() {
        val a = SoundCache().renderFixed(Waveform.SQUARE)
        val b = SoundCache().renderFixed(Waveform.SQUARE)
        assertTrue(a.select.samples.contentEquals(b.select.samples))
        assertTrue(a.tick(3, TickKind.NORMAL).samples.contentEquals(b.tick(3, TickKind.NORMAL).samples))
        assertTrue(a.windDown.samples.contentEquals(b.windDown.samples))
        val other = SoundCache().renderFixed(Waveform.SINE)
        assertFalse(a.select.samples.contentEquals(other.select.samples))
        assertFalse(a.windDown.samples.contentEquals(other.windDown.samples))
    }

    @Test
    fun changingTheWaveformReRendersTheWindDownClipToo() {
        val cache = SoundCache()
        val saw = cache.prepare(Waveform.SAW).fixed.windDown
        val pink = cache.prepare(Waveform.PINK_NOISE).fixed.windDown
        assertNotEquals(saw, pink)
        // the new active set carries its own clip, identical to a cold render of that waveform
        assertSame(pink, cache.active()!!.fixed.windDown)
        assertTrue(pink.samples.contentEquals(SoundCache().renderFixed(Waveform.PINK_NOISE).windDown.samples))
        // going back re-renders SAW rather than serving the noise clip
        assertTrue(saw.samples.contentEquals(cache.prepare(Waveform.SAW).fixed.windDown.samples))
    }

    @Test
    fun motifClipsAreLazyAndMemoised() {
        val cache = SoundCache()
        val set = cache.prepare(Waveform.SAW)
        assertEquals(0, set.motifCount)
        assertNull(set.peekMotif("com.plexapp.android", AppCategory.VIDEO, 0))

        val first = cache.motifClip(Waveform.SAW, "com.plexapp.android", AppCategory.VIDEO, 0)
        assertEquals(1, set.motifCount)
        assertSame(first, set.peekMotif("com.plexapp.android", AppCategory.VIDEO, 0))
        assertSame(first, cache.motifClip(Waveform.SAW, "com.plexapp.android", AppCategory.VIDEO, 0))
        assertEquals(1, set.motifCount)

        // a re-roll, another category and another app each get their own clip
        val rerolled = set.motifClip("com.plexapp.android", AppCategory.VIDEO, 1)
        val tools = set.motifClip("com.plexapp.android", AppCategory.TOOLS, 0)
        val other = set.motifClip("com.netflix.ninja", AppCategory.VIDEO, 0)
        assertEquals(4, set.motifCount)
        assertFalse(first.samples.contentEquals(rerolled.samples))
        assertFalse(first.samples.contentEquals(tools.samples))
        assertFalse(first.samples.contentEquals(other.samples))

        // sensible clip: a few hundred ms, quiet but present, finite, ending quietly
        for (clip in listOf(first, rerolled, tools, other)) {
            val seconds = clip.size.toDouble() / SAMPLE_RATE
            assertTrue("length $seconds s", seconds in 0.3..0.7)
            assertTrue(TestDsp.allFinite(clip.samples))
            assertTrue(TestDsp.peak(clip.samples) in 0.001..0.891)
            assertTrue(TestDsp.peak(clip.samples.copyOfRange(clip.size - 16, clip.size)) < 1e-4)
        }

        // evicting stale re-rolls drops only the other counts of that package
        set.evictOtherRerolls("com.plexapp.android", 1)
        assertNull(set.peekMotif("com.plexapp.android", AppCategory.VIDEO, 0))
        assertNull(set.peekMotif("com.plexapp.android", AppCategory.TOOLS, 0))
        assertNotNull(set.peekMotif("com.plexapp.android", AppCategory.VIDEO, 1))
        assertNotNull(set.peekMotif("com.netflix.ninja", AppCategory.VIDEO, 0))
    }

    @Test
    fun motifClipsAreIdenticalAcrossCachesAndThreads() {
        val reference = SoundCache().motifClip(Waveform.TRIANGLE, "com.spotify.tv.android", AppCategory.MUSIC, 0)
        val cache = SoundCache()
        val set = cache.prepare(Waveform.TRIANGLE)
        val results = arrayOfNulls<Clip>(6)
        val start = CountDownLatch(1)
        val workers = List(6) { i ->
            thread {
                start.await()
                results[i] = set.motifClip("com.spotify.tv.android", AppCategory.MUSIC, 0)
            }
        }
        start.countDown()
        workers.forEach { it.join(20_000) }
        for (r in results) {
            assertNotNull(r)
            assertTrue(reference.samples.contentEquals(r!!.samples))
        }
        assertEquals(1, set.motifCount)
        // all threads end up with the one stored clip
        for (r in results) assertSame(set.peekMotif("com.spotify.tv.android", AppCategory.MUSIC, 0), r)
    }

    @Test
    fun theOldSetKeepsPlayingUntilTheNewFixedSetIsReady() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val cache = SoundCache { waveform ->
            if (calls.incrementAndGet() > 1) {
                entered.countDown()
                gate.await(20, TimeUnit.SECONDS) // the second waveform renders slowly
            }
            SpecRenderer { specs -> FloatArray(2400 + specs.size) { 0.01f * (waveform.ordinal + 1) } }
        }
        val saw = cache.prepare(Waveform.SAW)
        assertSame(saw, cache.active())

        val worker = thread { cache.prepare(Waveform.SINE) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // the new set is still being rendered: playback keeps the old, complete one
        assertSame(saw, cache.active())
        assertEquals(Waveform.SAW, cache.active()!!.waveform)
        assertNotNull(cache.active()!!.fixed.select)
        gate.countDown()
        worker.join(10_000)
        assertEquals(Waveform.SINE, cache.active()!!.waveform)
        assertEquals(2, calls.get())
        // asking for the active waveform again renders nothing
        cache.prepare(Waveform.SINE)
        assertEquals(2, calls.get())
    }

    @Test
    fun renderAllRendersEachUnmutedAppOnceAtItsRerollCount() {
        val cache = SoundCache()
        val apps = listOf(
            MotifTarget("com.plexapp.android", AppCategory.VIDEO),
            MotifTarget("com.plexapp.android", AppCategory.VIDEO), // a second launcher activity of the same app
            MotifTarget("com.spotify.tv.android", AppCategory.MUSIC),
            MotifTarget("com.retroarch", AppCategory.GAMES),
        )
        val overrides = mapOf(
            "com.spotify.tv.android" to MotifOverride(rerollCount = 3),
            "com.retroarch" to MotifOverride(muted = true),
        )
        val progress = ArrayList<Pair<Int, Int>>()
        cache.renderAll(Waveform.SAW, apps, overrides) { done, total -> progress += done to total }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        val set = cache.active()!!
        assertEquals(2, set.motifCount)
        assertNotNull(set.peekMotif("com.plexapp.android", AppCategory.VIDEO, 0))
        assertNotNull(set.peekMotif("com.spotify.tv.android", AppCategory.MUSIC, 3))
        assertNull(set.peekMotif("com.spotify.tv.android", AppCategory.MUSIC, 0))
        assertNull(set.peekMotif("com.retroarch", AppCategory.GAMES, 0))

        // after a re-roll the old tune is evicted and the new one rendered
        cache.renderAll(Waveform.SAW, apps, overrides + ("com.spotify.tv.android" to MotifOverride(rerollCount = 4)))
        assertNull(set.peekMotif("com.spotify.tv.android", AppCategory.MUSIC, 3))
        assertNotNull(set.peekMotif("com.spotify.tv.android", AppCategory.MUSIC, 4))
        assertEquals(2, set.motifCount)
    }

    @Test
    fun progressCanAbortARender() {
        val cache = SoundCache()
        var seen = 0
        try {
            cache.renderAll(Waveform.SAW, forty(), emptyMap()) { done, _ ->
                seen = done
                if (done >= 3) throw IllegalStateException("cancelled")
            }
            org.junit.Assert.fail("should have aborted")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(3, seen)
        assertEquals(3, cache.active()!!.motifCount)
    }

    @Test
    fun changingTheWaveformStartsAFreshMotifSet() {
        val cache = SoundCache()
        cache.renderAll(Waveform.SAW, forty().take(5), emptyMap())
        val saw = cache.active()!!
        assertEquals(5, saw.motifCount)
        cache.renderAll(Waveform.PINK_NOISE, forty().take(5), emptyMap())
        val pink = cache.active()!!
        assertEquals(Waveform.PINK_NOISE, pink.waveform)
        assertEquals(5, pink.motifCount)
        // the old set object is untouched (a sound already scheduled from it stays valid)
        assertEquals(5, saw.motifCount)
        val a = saw.peekMotif("com.example.app0", AppCategory.VIDEO, 0)!!
        val b = pink.peekMotif("com.example.app0", AppCategory.VIDEO, 0)!!
        assertFalse(a.samples.contentEquals(b.samples))
    }

    @Test
    fun coldRenderOfFortyAppsIsFast() {
        fun pass(): Long {
            val cache = SoundCache()
            val t0 = System.nanoTime()
            cache.renderAll(Waveform.SAW, forty(), emptyMap())
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(40, cache.active()!!.motifCount)
            return ms
        }
        val cold = pass()
        val warm = pass()
        // one extra: every other waveform, to show the noise and supersaw voices cost about the same
        val timings = StringBuilder()
        for (wf in Waveform.values()) {
            val cache = SoundCache()
            val t0 = System.nanoTime()
            cache.renderAll(wf, forty(), emptyMap())
            timings.append("${wf.name} ${(System.nanoTime() - t0) / 1_000_000} ms  ")
        }
        println("cold render, fixed set + 40 app motifs (SAW): first pass $cold ms, second pass $warm ms")
        println("fixed set + 40 app motifs per waveform: $timings")
        assertTrue("40 apps took $cold ms", cold < 5000)
    }

    @Test
    fun theFixedSetAloneIsQuick() {
        val t0 = System.nanoTime()
        SoundCache().renderFixed(Waveform.SUPERSAW)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("fixed set render (SUPERSAW): $ms ms")
        assertTrue(ms < 5000)
    }
}

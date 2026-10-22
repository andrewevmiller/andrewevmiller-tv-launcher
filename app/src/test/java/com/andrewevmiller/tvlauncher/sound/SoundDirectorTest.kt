package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundDirectorTest {

    /** Small, fast, distinguishable clips: the content depends on the first note's frequency, length and gain. */
    private val fakeFactory: (Waveform) -> SpecRenderer = { _ ->
        SpecRenderer { specs ->
            val first = specs[0]
            val seed = ((first.freq * 7 + first.dur * 1300 + first.gain * 10000).toInt() % 190) + 5
            FloatArray(2000 + (first.dur * 10000).toInt() + specs.size) { i -> 0.001f * seed * (1 + i % 3) }
        }
    }

    private class Rig(factory: (Waveform) -> SpecRenderer, prepared: Boolean = true) {
        var allowedNow = true
        var now = 1000L
        var unitNow = 0.5
        var overridesNow: Map<String, MotifOverride> = emptyMap()
        val errors = ArrayList<String>()
        val background = ArrayList<() -> Unit>()
        var wakes = 0
        val cache = SoundCache(factory).also { if (prepared) it.prepare(Waveform.SAW) }
        val mixer = Mixer()
        val director = SoundDirector(
            cache = cache,
            mixer = mixer,
            clock = { now },
            allowed = { allowedNow },
            overrides = { overridesNow },
            unit = { unitNow },
            background = { background += it },
            wakeWriter = { wakes++ },
            onError = { what, _ -> errors += what },
        )
        val fixed: FixedClips get() = cache.active()!!.fixed

        fun drain(n: Int = 20000): FloatArray {
            val out = FloatArray(n)
            mixer.mix(out)
            mixer.clear()
            return out
        }
    }

    private fun assertPlays(clip: Clip, out: FloatArray, offset: Int = 0) {
        for (i in 0 until clip.size) assertEquals("sample $i", clip.samples[i], out[offset + i], 1e-6f)
    }

    private fun assertSilent(out: FloatArray, from: Int = 0, to: Int = out.size) {
        for (i in from until to) assertEquals("sample $i", 0f, out[i], 0f)
    }

    @Test
    fun fixedSoundsPlayTheirClips() {
        val rig = Rig(fakeFactory)
        val d = rig.director
        d.edge(); assertPlays(rig.fixed.edge, rig.drain())
        d.select(); assertPlays(rig.fixed.select, rig.drain())
        d.back(); assertPlays(rig.fixed.back, rig.drain())
        d.sleep(); assertPlays(rig.fixed.sleep, rig.drain())
        d.wake(); assertPlays(rig.fixed.wake, rig.drain())
        assertEquals(5, rig.wakes)
        assertTrue(rig.errors.isEmpty())
    }

    @Test
    fun tickPicksTheRowClipAndClampsTheRow() {
        val rig = Rig(fakeFactory)
        for (row in listOf(0, 2, 7, 11)) {
            rig.now += 1000
            rig.director.tick(row)
            assertPlays(rig.fixed.tick(row, TickKind.NORMAL), rig.drain())
        }
        rig.now += 1000
        rig.director.tick(40)
        assertPlays(rig.fixed.tick(11, TickKind.NORMAL), rig.drain())
        rig.now += 1000
        rig.director.tick(-3)
        assertPlays(rig.fixed.tick(0, TickKind.NORMAL), rig.drain())
    }

    @Test
    fun keyRepeatPlaysTheHeldTickAndOnlySoundingTicksCount() {
        val rig = Rig(fakeFactory)
        assertFalse(rig.fixed.tick(1, TickKind.NORMAL).samples.contentEquals(rig.fixed.tick(1, TickKind.HELD).samples))

        rig.now = 5000
        rig.director.tick(1)
        assertPlays(rig.fixed.tick(1, TickKind.NORMAL), rig.drain()) // the first tick is normal
        rig.now = 5050
        rig.director.tick(1)
        assertPlays(rig.fixed.tick(1, TickKind.HELD), rig.drain()) // 50 ms later: key repeat
        rig.now = 5200
        rig.director.tick(1)
        assertPlays(rig.fixed.tick(1, TickKind.NORMAL), rig.drain()) // 150 ms later: a fresh move

        // a tick that was gated off does not update the throttle (the mockup returns before touching `last`)
        rig.now = 5250
        rig.allowedNow = false
        rig.director.tick(1)
        assertSilent(rig.drain())
        rig.allowedNow = true
        rig.now = 5300 // 100 ms after the last sounding tick, 50 after the silent one
        rig.director.tick(1)
        assertPlays(rig.fixed.tick(1, TickKind.NORMAL), rig.drain())
    }

    @Test
    fun nothingPlaysWhenSoundsAreNotAllowed() {
        val rig = Rig(fakeFactory)
        rig.allowedNow = false
        val d = rig.director
        d.tick(0); d.edge(); d.select(); d.back(); d.sleep(); d.wake()
        d.launch("com.plexapp.android", AppCategory.VIDEO)
        d.playPreview()
        assertSilent(rig.drain())
        assertEquals(0, rig.wakes)
        assertTrue(rig.background.isEmpty())
        assertTrue(rig.errors.isEmpty())
    }

    @Test
    fun nothingPlaysBeforeTheFirstRenderIsDone() {
        val rig = Rig(fakeFactory, prepared = false)
        val d = rig.director
        d.tick(0); d.select(); d.launch("com.x", AppCategory.OTHER); d.playPreview()
        assertSilent(rig.drain())
        assertTrue(rig.errors.isEmpty())
    }

    @Test
    fun launchPlaysTheAppsOwnMotifHumanized() {
        val rig = Rig(fakeFactory)
        val set = rig.cache.active()!!
        val pkg = "com.spotify.tv.android"

        // not rendered yet: the render is handed to the background, nothing plays on the calling thread
        rig.director.launch(pkg, AppCategory.MUSIC)
        assertSilent(rig.drain())
        assertEquals(1, rig.background.size)
        rig.background.removeAt(0)()
        val clip = set.peekMotif(pkg, AppCategory.MUSIC, 0)!!
        assertPlays(clip, rig.drain()) // unit 0.5 -> exactly 0 cents and gain 1

        // rendered now: the next launch plays at once
        rig.director.launch(pkg, AppCategory.MUSIC)
        assertTrue(rig.background.isEmpty())
        assertPlays(clip, rig.drain())

        // humanize extremes: -5 cents and 0.9x level, through the same mixer maths as the real thing
        rig.unitNow = 0.0
        rig.director.launch(pkg, AppCategory.MUSIC)
        val low = rig.drain()
        val expected = Mixer().also { it.add(clip, rateForCents(-5.0), 0.9f, 0) }.let { m ->
            FloatArray(20000).also { m.mix(it) }
        }
        for (i in low.indices) assertEquals(expected[i], low[i], 1e-6f)
        assertTrue(rig.errors.isEmpty())
    }

    @Test
    fun mutedAppsLaunchSilently() {
        val rig = Rig(fakeFactory)
        rig.overridesNow = mapOf("com.annoying" to MotifOverride(muted = true))
        rig.director.launch("com.annoying", AppCategory.GAMES)
        assertSilent(rig.drain())
        assertTrue(rig.background.isEmpty())
        assertEquals(0, rig.wakes)
        // other apps are unaffected
        rig.director.launch("com.fine", AppCategory.GAMES)
        rig.background.removeAt(0)()
        assertFalse(rig.drain().all { it == 0f })
    }

    @Test
    fun rerolledAppsPlayTheirNewMotif() {
        val rig = Rig(fakeFactory)
        val set = rig.cache.active()!!
        val pkg = "com.plexapp.android"
        val original = set.motifClip(pkg, AppCategory.VIDEO, 0)
        rig.overridesNow = mapOf(pkg to MotifOverride(rerollCount = 2))
        val rerolled = set.motifClip(pkg, AppCategory.VIDEO, 2)
        assertFalse(original.samples.contentEquals(rerolled.samples))
        rig.director.launch(pkg, AppCategory.VIDEO)
        assertPlays(rerolled, rig.drain())
    }

    @Test
    fun aMotifThatCannotBeRenderedFallsBackToTheGenericLaunch() {
        val rig = Rig(
            factory = { _ ->
                SpecRenderer { specs ->
                    // a motif ends with its sub (one voice, 0.26 s, no fold); no fixed sound looks like that
                    if (specs.size > 1 && specs.last().let { it.dur == 0.26 && it.voices == 1 && it.fold == 0.0 }) {
                        throw IllegalStateException("synth failed")
                    }
                    FloatArray(2500 + specs.size) { 0.02f }
                }
            },
        )
        rig.director.launch("com.broken", AppCategory.OTHER)
        rig.background.removeAt(0)()
        assertPlays(rig.fixed.launchFallback, rig.drain())
        assertEquals(listOf("motif render for com.broken"), rig.errors)
    }

    @Test
    fun previewSchedulesTwoTicksSelectAndPlexThroughTheMixer() {
        val rig = Rig(fakeFactory)
        val set = rig.cache.active()!!
        rig.unitNow = 0.0 // would humanize a launch; the preview must ignore it
        rig.director.playPreview()
        assertEquals(1, rig.wakes)

        val tick = rig.fixed.tick(2, TickKind.NORMAL)
        val select = rig.fixed.select
        val plex = set.peekMotif("com.plexapp.android", AppCategory.VIDEO, 0)!!
        val out = rig.drain(msToSamples(480) + plex.size + 100)

        assertPlays(tick, out, 0)
        assertSilent(out, tick.size, msToSamples(110))
        assertPlays(tick, out, msToSamples(110))
        assertSilent(out, msToSamples(110) + tick.size, msToSamples(240))
        assertPlays(select, out, msToSamples(240))
        assertSilent(out, msToSamples(240) + select.size, msToSamples(480))
        assertPlays(plex, out, msToSamples(480))
        assertSilent(out, msToSamples(480) + plex.size)
    }

    @Test
    fun previewUsesTheNewestWaveformSetOnceItIsActive() {
        val rig = Rig(fakeFactory)
        rig.cache.prepare(Waveform.SQUARE)
        rig.director.playPreview()
        assertEquals(Waveform.SQUARE, rig.cache.active()!!.waveform)
        assertPlays(rig.fixed.tick(2, TickKind.NORMAL), rig.drain(), 0)
    }

    @Test
    fun noEntryPointEverThrows() {
        val rig = Rig(fakeFactory)
        val failing = SoundDirector(
            cache = rig.cache,
            mixer = rig.mixer,
            clock = { throw IllegalStateException("clock") },
            allowed = { throw IllegalStateException("settings") },
            overrides = { throw IllegalStateException("overrides") },
            unit = { throw IllegalStateException("random") },
            background = { throw IllegalStateException("executor") },
            wakeWriter = { throw IllegalStateException("writer") },
            onError = { what, _ -> rig.errors += what },
        )
        failing.tick(1); failing.edge(); failing.select(); failing.back(); failing.sleep(); failing.wake()
        failing.launch("com.x", AppCategory.OTHER)
        failing.playPreview()
        assertEquals(8, rig.errors.size)

        // a failing writer wake-up after the voice is queued is reported, not thrown
        val wakeFails = SoundDirector(
            cache = rig.cache, mixer = rig.mixer, clock = { 1L }, allowed = { true }, overrides = { emptyMap() },
            unit = { 0.5 }, background = { it() }, wakeWriter = { throw IllegalStateException("writer") },
            onError = { what, _ -> rig.errors += what },
        )
        wakeFails.select()
        assertEquals(9, rig.errors.size)
    }

    @Test
    fun overlappingSoundsMixAndNeverClip() {
        val rig = Rig(fakeFactory)
        rig.director.select()
        rig.director.edge()
        rig.director.back()
        rig.director.sleep()
        val out = rig.drain(5000)
        val sum = FloatArray(5000)
        for (c in listOf(rig.fixed.select, rig.fixed.edge, rig.fixed.back, rig.fixed.sleep)) {
            for (i in 0 until minOf(5000, c.size)) sum[i] += c.samples[i]
        }
        for (i in out.indices) assertEquals(softLimit(sum[i]), out[i], 1e-6f)
    }
}

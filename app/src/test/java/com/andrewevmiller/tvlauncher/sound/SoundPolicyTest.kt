package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.andrewevmiller.tvlauncher.sound.TickThrottle.Tick

class SoundPolicyTest {
    // --- tick throttle ---

    @Test fun firstTickIsNormal() = assertEquals(Tick.NORMAL, TickThrottle().next(1_000L))

    @Test fun aTickWithinTheFloorIsSkippedAndDoesNotCountAsPlayed() {
        val t = TickThrottle()
        assertEquals(Tick.NORMAL, t.next(1_000L))
        assertEquals(Tick.NONE, t.next(1_010L))
        assertEquals(Tick.NONE, t.next(1_034L))
        // measured from the tick that played at 1000, not from the skipped ones
        assertEquals(Tick.HELD, t.next(1_035L))
    }

    @Test fun tickBetweenFloorAndHeldWindowIsHeld() {
        val t = TickThrottle()
        t.next(0L)
        assertEquals(Tick.HELD, t.next(60L))
        assertEquals(Tick.HELD, t.next(60L + 89L))
    }

    @Test fun tickAfterTheHeldWindowIsNormalAgain() {
        val t = TickThrottle()
        t.next(0L)
        assertEquals(Tick.NORMAL, t.next(90L))
        assertEquals(Tick.NORMAL, t.next(90L + 500L))
    }

    @Test fun heldKeyRepeatAt50msThinsOutToHeldTicks() {
        val t = TickThrottle()
        val kinds = (0 until 6).map { t.next(it * 50L) }
        assertEquals(listOf(Tick.NORMAL, Tick.HELD, Tick.HELD, Tick.HELD, Tick.HELD, Tick.HELD), kinds)
    }

    @Test fun veryFastRepeatsOnlyPlayEveryFloor() {
        val t = TickThrottle()
        val played = (0 until 100).count { t.next(it * 10L) != Tick.NONE }
        // 100 calls over 990 ms with a 35 ms floor: one tick per 40 ms (every fourth call)
        assertEquals(25, played)
    }

    @Test fun clockMovingBackwardsPlaysANormalTick() {
        val t = TickThrottle()
        t.next(10_000L)
        assertEquals(Tick.NORMAL, t.next(5_000L))
        assertEquals(Tick.HELD, t.next(5_050L))
    }

    // --- cached sound files ---

    @Test fun fileNamesCarryTheWaveformAndTheRenderVersion() {
        assertEquals("fs_tick_saw_v${SoundFiles.RENDER_VERSION}.wav", SoundFiles.name("tick", Waveform.SAW))
        assertEquals("fs_tick_sine_v${SoundFiles.RENDER_VERSION}.wav", SoundFiles.name("tick", Waveform.SINE))
    }

    @Test fun staleFilesAreOtherWaveformsOldVersionsAndTheOldUnversionedNames() {
        val keep = setOf(SoundFiles.name("tick", Waveform.SAW), SoundFiles.name("edge", Waveform.SAW))
        val existing = listOf(
            SoundFiles.name("tick", Waveform.SAW), SoundFiles.name("edge", Waveform.SAW),
            SoundFiles.name("tick", Waveform.SINE),
            "fs_tick.wav", "fs_select.wav", // written by builds before the cache was versioned
            "fs_tick_saw_v0.wav",
            SoundFiles.name("select", Waveform.SAW) + ".tmp",
            "apps.snapshot", "somebody_elses.wav", // not ours: never touched
        )
        assertEquals(
            listOf(
                SoundFiles.name("tick", Waveform.SINE), "fs_tick.wav", "fs_select.wav", "fs_tick_saw_v0.wav",
                SoundFiles.name("select", Waveform.SAW) + ".tmp",
            ),
            SoundFiles.stale(existing, keep),
        )
    }

    // --- motif cache ---

    private fun key(pkg: String, wf: Waveform = Waveform.SAW, c: AppCategory = AppCategory.VIDEO) = MotifKey(pkg, c, wf)

    @Test fun cacheKeysDistinguishPackageWaveformAndCategory() {
        val cache = MotifCache()
        cache.put(key("a"), shortArrayOf(1))
        assertNotNull(cache.get(key("a")))
        assertNull(cache.get(key("b")))
        assertNull(cache.get(key("a", wf = Waveform.SINE)))
        assertNull(cache.get(key("a", c = AppCategory.MUSIC)))
    }

    @Test fun cacheEvictsTheLeastRecentlyUsed() {
        val cache = MotifCache(maxEntries = 3)
        cache.put(key("a"), shortArrayOf(1)); cache.put(key("b"), shortArrayOf(2)); cache.put(key("c"), shortArrayOf(3))
        cache.get(key("a")) // a is now the most recently used
        cache.put(key("d"), shortArrayOf(4))
        assertEquals(3, cache.size())
        assertFalse("b was the least recently used", cache.contains(key("b")))
        assertTrue(cache.contains(key("a")) && cache.contains(key("c")) && cache.contains(key("d")))
    }

    @Test fun clearEmptiesTheCache() {
        val cache = MotifCache()
        cache.put(key("a"), shortArrayOf(1))
        cache.clear()
        assertEquals(0, cache.size())
    }

    // --- fixed sounds ---

    @Test fun theFixedSetHasTheFiveSoundsTheEngineAndCacheExpect() {
        assertEquals(listOf("tick", "tick_held", "edge", "select", "back"), FixedSounds.all.map { it.name })
        assertTrue(FixedSounds.all.all { it.voices.isNotEmpty() && it.seconds > 0 })
    }
}

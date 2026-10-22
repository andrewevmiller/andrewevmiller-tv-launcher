package com.andrewevmiller.tvlauncher.sound

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundPolicyTest {

    @Test
    fun firstTickIsNormal() {
        assertEquals(TickKind.NORMAL, TickThrottle().classify(10_000L))
    }

    @Test
    fun tickFiftyMsAfterThePreviousIsHeld() {
        val t = TickThrottle()
        assertEquals(TickKind.NORMAL, t.classify(1_000L))
        assertEquals(TickKind.HELD, t.classify(1_050L))
    }

    @Test
    fun tickEightyNineMsAfterThePreviousIsStillHeld() {
        val t = TickThrottle()
        t.classify(1_000L)
        assertEquals(TickKind.HELD, t.classify(1_089L))
    }

    @Test
    fun tickExactlyNinetyMsAfterThePreviousIsNormal() {
        // Held only when strictly less than 90 ms, matching the mockup's `now - last < 90`.
        val t = TickThrottle()
        t.classify(1_000L)
        assertEquals(TickKind.NORMAL, t.classify(1_090L))
    }

    @Test
    fun keyRepeatEvery40MsStaysHeldAfterTheFirstTick() {
        val t = TickThrottle()
        assertEquals(TickKind.NORMAL, t.classify(0L))
        for (k in 1..25) {
            assertEquals("repeat tick $k", TickKind.HELD, t.classify(40L * k))
        }
    }

    @Test
    fun pauseThenNormalAgain() {
        val t = TickThrottle()
        assertEquals(TickKind.NORMAL, t.classify(0L))
        assertEquals(TickKind.HELD, t.classify(40L))
        assertEquals(TickKind.NORMAL, t.classify(200L))   // 160 ms since the last tick
        assertEquals(TickKind.HELD, t.classify(230L))
    }

    @Test
    fun clockGoingBackwardsIsNormal() {
        val t = TickThrottle()
        assertEquals(TickKind.NORMAL, t.classify(1_000L))
        assertEquals(TickKind.NORMAL, t.classify(500L))
    }

    @Test
    fun clockGoingBackwardsResetsTheReference() {
        val t = TickThrottle()
        t.classify(1_000L)
        t.classify(500L)                                  // backwards: NORMAL, reference becomes 500
        assertEquals(TickKind.HELD, t.classify(520L))     // 20 ms after 500
    }

    @Test
    fun customHeldWindowIsRespected() {
        val t = TickThrottle(heldWindowMs = 10)
        t.classify(0L)
        assertEquals(TickKind.HELD, t.classify(9L))
        assertEquals(TickKind.NORMAL, t.classify(19L))
    }

    @Test
    fun soundsAllowedTruthTable() {
        assertEquals(true, soundsAllowed(launcherToggle = true, systemSoundEffects = true))
        assertEquals(false, soundsAllowed(launcherToggle = true, systemSoundEffects = false))
        assertEquals(false, soundsAllowed(launcherToggle = false, systemSoundEffects = true))
        assertEquals(false, soundsAllowed(launcherToggle = false, systemSoundEffects = false))
    }
}

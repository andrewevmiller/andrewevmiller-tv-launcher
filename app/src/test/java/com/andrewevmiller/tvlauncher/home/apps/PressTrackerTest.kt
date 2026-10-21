package com.andrewevmiller.tvlauncher.home.apps

import org.junit.Assert.assertEquals
import org.junit.Test

/** Long-press OK (edit mode) versus a tap (launch): one press gives exactly one of the two. */
class PressTrackerTest {
    @Test
    fun aQuickPressIsATapOnRelease() {
        val t = PressTracker()
        assertEquals(PressAction.NONE, t.onDown(0, false))
        assertEquals(PressAction.TAP, t.onUp())
    }

    @Test
    fun theFirstRepeatIsALongPressAndTheReleaseThenDoesNothing() {
        val t = PressTracker()
        assertEquals(PressAction.NONE, t.onDown(0, false))
        assertEquals(PressAction.LONG_PRESS, t.onDown(1, true))
        assertEquals(PressAction.NONE, t.onUp())
    }

    @Test
    fun holdingFiresTheLongPressOnlyOnce() {
        val t = PressTracker()
        t.onDown(0, false)
        assertEquals(PressAction.LONG_PRESS, t.onDown(1, true))
        for (n in 2..10) assertEquals(PressAction.NONE, t.onDown(n, false))
        assertEquals(PressAction.NONE, t.onUp())
    }

    @Test
    fun aRepeatCountAloneCountsAsAHold() {
        val t = PressTracker()
        t.onDown(0, false)
        assertEquals(PressAction.LONG_PRESS, t.onDown(1, false))
    }

    @Test
    fun theLongPressFlagAloneCountsAsAHold() {
        val t = PressTracker()
        assertEquals(PressAction.LONG_PRESS, t.onDown(0, true))
        assertEquals(PressAction.NONE, t.onUp())
    }

    @Test
    fun repeatsWithoutAnObservedFirstDownAreIgnored() {
        val t = PressTracker()
        assertEquals(PressAction.NONE, t.onDown(3, true))
        assertEquals(PressAction.NONE, t.onUp())
    }

    @Test
    fun aReleaseWithoutAPressDoesNothing() {
        assertEquals(PressAction.NONE, PressTracker().onUp())
    }

    @Test
    fun cancellingMidPressSuppressesTheTapAndTheLongPress() {
        val t = PressTracker()
        t.onDown(0, false)
        t.cancel()
        assertEquals(PressAction.NONE, t.onDown(1, true))
        assertEquals(PressAction.NONE, t.onUp())
    }

    @Test
    fun theNextPressAfterALongPressIsANormalTap() {
        val t = PressTracker()
        t.onDown(0, false)
        t.onDown(1, true)
        t.onUp()
        assertEquals(PressAction.NONE, t.onDown(0, false))
        assertEquals(PressAction.TAP, t.onUp())
    }

    @Test
    fun aNewDownResetsAPressWhoseReleaseWasLost() {
        val t = PressTracker()
        t.onDown(0, false)
        t.onDown(1, true) // long press, release never arrives
        assertEquals(PressAction.NONE, t.onDown(0, false))
        assertEquals(PressAction.TAP, t.onUp())
    }
}

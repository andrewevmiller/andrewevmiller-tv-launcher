package com.andrewevmiller.tvlauncher.sleep

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SleepDimHandshakeTest {
    @Before
    fun clean() = SleepDimHandshake.reset()

    @After
    fun cleanAfter() = SleepDimHandshake.reset()

    @Test
    fun notShownUntilTheActivityReportsIt() {
        SleepDimHandshake.request(1_000)
        assertFalse(SleepDimHandshake.isShown())
        assertTrue(SleepDimHandshake.shown(1_250))
        assertTrue(SleepDimHandshake.isShown())
    }

    @Test
    fun aNewRequestForgetsTheLastShowing() {
        SleepDimHandshake.request(1_000)
        SleepDimHandshake.shown(1_100)
        assertTrue(SleepDimHandshake.isShown())
        SleepDimHandshake.request(5_000)
        assertFalse(SleepDimHandshake.isShown())
    }

    @Test
    fun anActivityThatAppearsAfterTheStartWasAbandonedIsToldToClose() {
        SleepDimHandshake.request(1_000)
        SleepDimHandshake.abandon()
        assertFalse(SleepDimHandshake.shown(9_000))
        assertFalse(SleepDimHandshake.isShown())
    }

    @Test
    fun abandoningAfterTheActivityAlreadyShowedLeavesItShown() {
        SleepDimHandshake.request(1_000)
        assertTrue(SleepDimHandshake.shown(1_900))
        SleepDimHandshake.abandon() // too late: nothing to give up on
        assertTrue(SleepDimHandshake.isShown())
        assertTrue("a second start of the live activity still works", SleepDimHandshake.shown(5_000))
    }

    @Test
    fun aNewRequestAfterAnAbandonedOneWorksAgain() {
        SleepDimHandshake.request(1_000)
        SleepDimHandshake.abandon()
        SleepDimHandshake.request(20_000)
        assertTrue(SleepDimHandshake.shown(20_100))
        assertTrue(SleepDimHandshake.isShown())
    }

    @Test
    fun anActivityStartedWithoutAnyRequestJustRecordsItself() {
        assertFalse(SleepDimHandshake.isShown())
        assertTrue(SleepDimHandshake.shown(42))
        assertTrue(SleepDimHandshake.isShown())
    }
}

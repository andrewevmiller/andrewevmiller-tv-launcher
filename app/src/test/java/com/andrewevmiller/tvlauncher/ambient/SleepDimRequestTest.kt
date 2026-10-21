package com.andrewevmiller.tvlauncher.ambient

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SleepDimRequestTest {
    private val t0 = 5_000L

    @Before
    @After
    fun reset() = SleepDimRequest.clear()

    @Test
    fun nothingIsPendingByDefault() {
        assertFalse(SleepDimRequest.isPending(t0))
        assertFalse(SleepDimRequest.consume(t0))
    }

    @Test
    fun aRequestIsConsumedExactlyOnce() {
        SleepDimRequest.request(t0)
        assertTrue(SleepDimRequest.isPending(t0 + 1))
        assertTrue("peeking must not consume", SleepDimRequest.isPending(t0 + 1))
        assertTrue(SleepDimRequest.consume(t0 + 2))
        assertFalse(SleepDimRequest.consume(t0 + 3))
        assertFalse(SleepDimRequest.isPending(t0 + 3))
    }

    @Test
    fun aRequestIsLiveForThirtySecondsInclusive() {
        assertTrue(SleepDimRequest.EXPIRY_MILLIS == 30_000L)
        SleepDimRequest.request(t0)
        assertTrue(SleepDimRequest.consume(t0 + 30_000L))
    }

    @Test
    fun aStaleRequestCountsAsNotPendingAndIsDropped() {
        SleepDimRequest.request(t0)
        assertFalse(SleepDimRequest.isPending(t0 + 30_001L))
        assertFalse(SleepDimRequest.consume(t0 + 30_001L))
        // It is gone for good, even for a caller whose clock reads earlier again.
        assertFalse(SleepDimRequest.consume(t0 + 1L))
    }

    @Test
    fun aNormalScreensaverMinutesLaterIsNotDimmed() {
        SleepDimRequest.request(t0)
        // The dream never started; a normal screensaver kicks in 10 minutes later.
        assertFalse(SleepDimRequest.consume(t0 + 10 * 60_000L))
    }

    @Test
    fun aNewRequestRestartsTheWindow() {
        SleepDimRequest.request(t0)
        SleepDimRequest.request(t0 + 25_000L)
        assertTrue(SleepDimRequest.consume(t0 + 50_000L))
    }

    @Test
    fun aTimeBeforeTheRequestIsNotTrusted() {
        SleepDimRequest.request(t0)
        assertFalse(SleepDimRequest.consume(t0 - 1L))
    }

    @Test
    fun aTimestampOfZeroOrNegativeIsStillARealRequest() {
        SleepDimRequest.request(0L)
        assertTrue(SleepDimRequest.consume(10L))
        SleepDimRequest.request(-1_000L)
        assertTrue(SleepDimRequest.consume(-500L))
    }

    @Test
    fun clearForgetsTheRequest() {
        SleepDimRequest.request(t0)
        SleepDimRequest.clear()
        assertFalse(SleepDimRequest.consume(t0))
    }

    @Test
    fun theConvenienceFlagUsesTheRealClock() {
        assertFalse(SleepDimRequest.pending)
        SleepDimRequest.pending = true
        assertTrue(SleepDimRequest.pending)
        assertTrue(SleepDimRequest.consume())
        assertFalse(SleepDimRequest.pending)
        SleepDimRequest.pending = true
        SleepDimRequest.pending = false
        assertFalse(SleepDimRequest.consume())
    }
}

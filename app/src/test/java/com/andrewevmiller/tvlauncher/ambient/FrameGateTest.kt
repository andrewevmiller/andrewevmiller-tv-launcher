package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {
    /** Frames the gate would render when fed vsyncs at [hz] for [seconds], times rounded to ms like Choreographer's. */
    private fun rendered(hz: Double, seconds: Int, gate: FrameGate = FrameGate(30), jitterMs: (Int) -> Long = { 0L }): Int {
        var count = 0
        val total = (hz * seconds).toInt()
        for (i in 0 until total) {
            val t = Math.round(i * 1000.0 / hz) + jitterMs(i)
            if (gate.shouldRender(t)) count++
        }
        return count
    }

    @Test
    fun sixtyHertzPanelRendersThirtyFramesPerSecond() {
        val n = rendered(60.0, 10)
        assertTrue("rendered $n", n in 298..302)
    }

    @Test
    fun oneTwentyHertzPanelRendersThirtyFramesPerSecond() {
        val n = rendered(120.0, 10)
        assertTrue("rendered $n", n in 298..302)
    }

    @Test
    fun ninetyHertzPanelRendersThirtyFramesPerSecond() {
        val n = rendered(90.0, 10)
        assertTrue("rendered $n", n in 295..305)
    }

    @Test
    fun slowerPanelsAreNotThrottledFurther() {
        assertEquals(240, rendered(24.0, 10))
        assertEquals(300, rendered(30.0, 10))
    }

    @Test
    fun jitteredVsyncStillAveragesNearThirty() {
        val n = rendered(60.0, 10) { i -> (i % 3 - 1).toLong() } // -1, 0, +1 ms noise
        assertTrue("rendered $n", n in 280..320)
    }

    @Test
    fun neverRendersTwoFramesCloserThanAboutAHalfVsyncBelowThirtyFps() {
        val gate = FrameGate(30)
        var last = -1L
        for (i in 0 until 600) {
            val t = Math.round(i * 1000.0 / 60.0)
            if (gate.shouldRender(t)) {
                if (last >= 0) assertTrue("gap ${t - last}", t - last >= 29L)
                last = t
            }
        }
    }

    @Test
    fun firstFrameAlwaysRendersAndBackwardsTimeRestartsTheGate() {
        val gate = FrameGate(30)
        assertTrue(gate.shouldRender(1_000_000L))
        assertFalse(gate.shouldRender(1_000_010L))
        assertTrue(gate.shouldRender(5L)) // clock jumped back
        assertFalse(gate.shouldRender(10L))
    }

    @Test
    fun resetRendersTheNextFrameImmediately() {
        val gate = FrameGate(30)
        assertTrue(gate.shouldRender(0L))
        assertFalse(gate.shouldRender(5L))
        gate.reset()
        assertTrue(gate.shouldRender(6L))
    }
}

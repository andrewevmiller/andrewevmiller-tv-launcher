// purecheck:skip (Robolectric: renders the Android view)
package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real [OxyfieldView] on the JVM and checks the sleep fade on its pixels (no PNGs: those are in
 * [SleepDimScreenshotTest]). Frames are driven by hand through [SleepDimRig].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SleepDimViewTest {
    // Where the time text sits at 1920x1080 (see oxyfieldClockLayout): left 96, baseline 977, 80 px tall.
    private val clock = intArrayOf(96, 900, 420, 985)

    private fun SleepDimRig.clockBrightness(bitmap: android.graphics.Bitmap) =
        SleepDimRig.maxChannel(bitmap, clock[0], clock[1], clock[2], clock[3])

    @Test
    fun withoutSleepModeTheFrameIsTheNormalOne() {
        val rig = SleepDimRig()
        assertEquals(1920, rig.width)
        assertEquals(1080, rig.height)
        assertFalse(rig.view.isSleepDimming)
        rig.advanceTo(30_000L)
        val frame = rig.render()
        // The corner is plain background, exactly the palette's, and the clock is at full ink.
        assertEquals(rig.palette.bg, frame.getPixel(3, 3))
        assertTrue("clock text should be bright", rig.clockBrightness(frame) >= 220)
        assertFalse(rig.view.isSleepSettled)
    }

    @Test
    fun theFadeDarkensTheWholePictureAndSettlesOnTheFloor() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        assertTrue(rig.view.isSleepDimming)

        rig.advanceTo(0L)
        val start = rig.render()
        assertEquals("level is 1 at the start", rig.palette.bg, start.getPixel(3, 3))

        rig.advanceTo(22_000L)
        val mid = rig.render()
        val midBg = mid.getPixel(3, 3)
        assertTrue("mid-fade background is darker", (midBg and 0xFF) < (rig.palette.bg and 0xFF))
        assertTrue("and brighter than the floor", (midBg and 0xFF) > ((SleepDimMath.scaleColor(rig.palette.bg, SleepDimMath.FLOOR)) and 0xFF))
        assertFalse(rig.view.isSleepSettled)

        rig.advanceTo(45_000L)
        assertTrue(rig.view.isSleepSettled)
        val settled = rig.render()
        assertEquals(SleepDimMath.scaleColor(rig.palette.bg, SleepDimMath.FLOOR), settled.getPixel(3, 3))

        assertTrue(SleepDimRig.meanLevel(start) > SleepDimRig.meanLevel(mid))
        assertTrue(SleepDimRig.meanLevel(mid) > SleepDimRig.meanLevel(settled))
    }

    @Test
    fun theClockLeavesBeforeTheFadeEnds() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(0L)
        assertTrue("clock visible at 0 s", rig.clockBrightness(rig.render()) >= 220)

        // 60% of 45 s = 27 s. Dots at 28 s are at most ~0.36 of 185; text at full ink would be 236.
        rig.advanceTo(28_000L)
        val late = rig.clockBrightness(rig.render())
        assertTrue("clock gone by 28 s, brightest pixel in its box was $late", late < 120)
    }

    @Test
    fun theSettledFrameIsStillAndNothingMovesAfterIt() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(45_000L)
        assertTrue(rig.view.isSleepSettled)
        val first = rig.render()

        // More "frames" arrive (a misbehaving host); the picture must not change.
        rig.advanceTo(75_000L)
        rig.advanceTo(300_000L)
        val later = rig.render()
        assertTrue("settled frames should be identical", first.sameAs(later))
    }

    @Test
    fun theDotsAreStillFaintlyThereAtTheFloor() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(60_000L)
        val frame = rig.render()
        val floorBg = SleepDimMath.scaleColor(rig.palette.bg, SleepDimMath.FLOOR)
        val bgLevel = maxOf((floorBg shr 16) and 0xFF, (floorBg shr 8) and 0xFF, floorBg and 0xFF)
        // Near-black overall, but not a flat fill: something is lit above the background.
        assertTrue("mean ${SleepDimRig.meanLevel(frame)}", SleepDimRig.meanLevel(frame) < 6.0)
        assertTrue("some dots above the background", SleepDimRig.countAbove(frame, bgLevel) > 100)
        assertTrue("and none brighter than the floor allows", SleepDimRig.countAbove(frame, 15) == 0)
    }

    @Test
    fun clearingSleepModeRestoresTheNormalPicture() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(30_000L)
        rig.view.clearSleepDim()
        assertFalse(rig.view.isSleepDimming)
        val frame = rig.render()
        assertEquals(rig.palette.bg, frame.getPixel(3, 3))
        assertTrue(rig.clockBrightness(frame) >= 220)
    }

    @Test
    fun reducedMotionShowsTheDimStillFrameAtOnceWithNoClock() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.startSleepDim(rig.startMillis)
        assertTrue(rig.view.isSleepSettled)
        val frame = rig.render()
        assertEquals(SleepDimMath.scaleColor(rig.palette.bg, SleepDimMath.FLOOR), frame.getPixel(3, 3))
        assertTrue(rig.clockBrightness(frame) < 40)
        assertTrue(SleepDimRig.meanLevel(frame) < 6.0)
    }

    @Test
    fun reducedMotionWithoutSleepModeIsUnchanged() {
        val rig = SleepDimRig(reducedMotion = true)
        val frame = rig.render()
        assertEquals(rig.palette.bg, frame.getPixel(3, 3))
        assertTrue(rig.clockBrightness(frame) >= 220)
    }
}

package com.andrewevmiller.tvlauncher.ambient

import android.graphics.BitmapFactory
import com.andrewevmiller.tvlauncher.ui.ScreenshotSupport
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Oxyfield screensaver to PNGs in app/build/screenshots/: the normal screen with the seconds clock
 * (screensaver-*.png), and the sleep fade at 0, 10, 25, 45 and 60 s plus the reduced-motion frame (sleep-dim-*.png).
 * Off by default; run with FIELD_SCREENSHOTS=1 (see [ScreenshotSupport]). The frames are looked at by eye; the
 * asserts only guard the shape of the fade. The wall clock is fixed by [SleepDimRig], so frames are repeatable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SleepDimScreenshotTest {
    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

    private val timeRegion = intArrayOf(96, 900, 700, 985)

    private fun wallAt(hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().run {
            clear()
            set(2026, Calendar.OCTOBER, 21, hour, minute, second)
            timeInMillis
        }

    @Test
    fun rendersTheNormalScreensaverClockInBothHourModes() {
        // 24-hour, a typical time; 12-hour, the same time; 12-hour at the widest string, "12:59:59 PM".
        val cases = listOf(
            Triple("screensaver-24h", true, wallAt(19, 42, 7)),
            Triple("screensaver-12h", false, wallAt(19, 42, 7)),
            Triple("screensaver-12h-widest", false, wallAt(12, 59, 59)),
        )
        for ((name, is24, wall) in cases) {
            val rig = SleepDimRig(is24Hour = is24, wallMillis = wall)
            rig.advanceTo(4_000L) // a few ripples are out, some near the clock
            val file = rig.screenshot(name)
            val bitmap = BitmapFactory.decodeFile(file.path)
            val box = SleepDimRig.brightBounds(bitmap, 225)
            assertNotNull(box)
            println("$name: '${rig.view.clockTimeText}' time box ${box!!.toList()} of ${bitmap.width}x${bitmap.height}")
            assertTrue(box[0] >= 96 && box[2] <= 1824 && box[1] >= 54 && box[3] <= 1026)
        }
    }

    @Test
    fun rendersTheNormalReducedMotionScreensaver() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.screenshot("screensaver-reduced-motion")
    }

    @Test
    fun rendersTheSleepFade() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)

        val means = mutableListOf<Double>()
        val files = mutableListOf<java.io.File>()
        for (seconds in listOf(0, 10, 25, 45, 60)) {
            rig.advanceTo(seconds * 1000L)
            val file = rig.screenshot("sleep-dim-%02ds".format(seconds))
            files += file
            val bitmap = BitmapFactory.decodeFile(file.path)
            val mean = SleepDimRig.meanLevel(bitmap)
            means += mean
            println(
                "sleep-dim %2ds: mean=%.2f lit>12=%d lit>6=%d clockMax=%d text='%s'".format(
                    seconds, mean, SleepDimRig.countAbove(bitmap, 12), SleepDimRig.countAbove(bitmap, 6),
                    SleepDimRig.maxChannel(bitmap, timeRegion[0], timeRegion[1], timeRegion[2], timeRegion[3]),
                    rig.view.clockTimeText,
                ),
            )
        }
        for (i in 1 until means.size) {
            assertTrue("frame $i should not be brighter than frame ${i - 1}: $means", means[i] <= means[i - 1])
        }
        // The last two frames are the same still frame, faint clock included.
        assertEquals(files[3].readBytes().toList(), files[4].readBytes().toList())
        val settled = BitmapFactory.decodeFile(files[4].path)
        assertTrue(
            SleepDimRig.countExact(
                settled, SleepDimMath.scaleColor(rig.palette.ink, SleepDimMath.CLOCK_FLOOR),
                timeRegion[0], timeRegion[1], timeRegion[2], timeRegion[3],
            ) > 300,
        )
    }

    @Test
    fun rendersTheReducedMotionFrame() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.startSleepDim(rig.startMillis)
        rig.screenshot("sleep-dim-reduced-motion")
    }

    @Test
    fun rendersTheSleepFrameInTwelveHourMode() {
        val rig = SleepDimRig(is24Hour = false)
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(60_000L)
        rig.screenshot("sleep-dim-12h")
    }
}

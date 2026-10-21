package com.andrewevmiller.tvlauncher.ambient

import android.graphics.BitmapFactory
import com.andrewevmiller.tvlauncher.ui.ScreenshotSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Oxyfield sleep fade to PNGs at 0, 10, 25, 45 and 60 s (app/build/screenshots/sleep-dim-*.png).
 * Off by default; run with FIELD_SCREENSHOTS=1 (see [ScreenshotSupport]). The frames are looked at by eye; the
 * asserts only guard the shape of the fade.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SleepDimScreenshotTest {
    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

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
                "sleep-dim %2ds: mean=%.2f lit>12=%d lit>6=%d clockMax=%d".format(
                    seconds, mean, SleepDimRig.countAbove(bitmap, 12), SleepDimRig.countAbove(bitmap, 6),
                    SleepDimRig.maxChannel(bitmap, 96, 900, 420, 985),
                ),
            )
        }
        for (i in 1 until means.size) {
            assertTrue("frame $i should not be brighter than frame ${i - 1}: $means", means[i] <= means[i - 1])
        }
        // The last two frames are the same still frame.
        assertEquals(files[3].readBytes().toList(), files[4].readBytes().toList())
    }

    @Test
    fun rendersTheReducedMotionFrame() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.startSleepDim(rig.startMillis)
        rig.screenshot("sleep-dim-reduced-motion")
    }
}

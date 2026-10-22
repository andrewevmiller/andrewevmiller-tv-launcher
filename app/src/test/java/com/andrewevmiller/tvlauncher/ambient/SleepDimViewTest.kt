// purecheck:skip (Robolectric: renders the Android view)
package com.andrewevmiller.tvlauncher.ambient

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real [OxyfieldView] on the JVM and checks the clock and the sleep fade on its pixels (no PNGs: those
 * are in [SleepDimScreenshotTest]). Frames are driven by hand through [SleepDimRig], which also owns the wall clock
 * the view reads (19:42:07.250 unless a test moves it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SleepDimViewTest {
    // Where the clock sits at 1920x1080 (see oxyfieldClockLayout): left 96, time baseline 977 and 80 px tall,
    // date baseline 1019 and 26 px tall. The time is at most 11 characters of ~48 px.
    private val timeRegion = intArrayOf(96, 900, 700, 985)
    private val dateRegion = intArrayOf(96, 990, 700, 1030)
    private val clockRegion = intArrayOf(96, 900, 700, 1030)

    // A patch of plain dots above the clock, to compare the clock against.
    private val aroundRegion = intArrayOf(96, 600, 700, 880)

    private fun SleepDimRig.timeBrightness(bitmap: Bitmap) =
        SleepDimRig.maxChannel(bitmap, timeRegion[0], timeRegion[1], timeRegion[2], timeRegion[3])

    private fun SleepDimRig.aroundBrightness(bitmap: Bitmap) =
        SleepDimRig.maxChannel(bitmap, aroundRegion[0], aroundRegion[1], aroundRegion[2], aroundRegion[3])

    /** Fully covered pixels of the time text at clock brightness [level]: the glyphs are drawn in exactly this colour. */
    private fun SleepDimRig.timePixelsAt(bitmap: Bitmap, level: Float) =
        SleepDimRig.countExact(
            bitmap, SleepDimMath.scaleColor(palette.ink, level),
            timeRegion[0], timeRegion[1], timeRegion[2], timeRegion[3],
        )

    private fun SleepDimRig.datePixelsAt(bitmap: Bitmap, level: Float) =
        SleepDimRig.countExact(
            bitmap, SleepDimMath.scaleColor(palette.muted, level),
            dateRegion[0], dateRegion[1], dateRegion[2], dateRegion[3],
        )

    private fun inside(inner: IntArray, outer: IntArray) =
        inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3]

    private fun wallAt(hour: Int, minute: Int, second: Int, millis: Int = 0): Long =
        Calendar.getInstance().run {
            clear()
            set(2026, Calendar.OCTOBER, 21, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
            timeInMillis
        }

    /** Delivers a system broadcast to whatever the view registered (and runs what is due on the main looper). */
    private fun SleepDimRig.broadcast(action: String) {
        activity.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Delivers the system's minute tick. */
    private fun SleepDimRig.minuteTick() = broadcast(Intent.ACTION_TIME_TICK)

    /** What the clock should read for the rig's wall clock "now", in the JVM's default time zone. */
    private fun SleepDimRig.expectedTimeText(withSeconds: Boolean = true): String {
        val cal = Calendar.getInstance().apply { timeInMillis = wallMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return if (withSeconds) OxyfieldClockText.time(hour, minute, cal.get(Calendar.SECOND), is24Hour)
        else OxyfieldClockText.time(hour, minute, is24Hour)
    }

    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

    private fun SleepDimRig.clearInvalidated() = shadowOf(view).clearWasInvalidated()

    private fun SleepDimRig.wasInvalidated() = shadowOf(view).wasInvalidated()

    // ---- The normal screensaver clock: seconds -------------------------------------------------------------

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
        assertTrue("clock text should be bright", rig.timeBrightness(frame) >= 220)
        assertFalse(rig.view.isSleepSettled)
    }

    @Test
    fun theNormalClockShowsSecondsInTwentyFourHourMode() {
        val rig = SleepDimRig(is24Hour = true)
        rig.advanceTo(0L)
        rig.render()
        assertEquals("19:42:07", rig.view.clockTimeText)
    }

    @Test
    fun theNormalClockShowsSecondsInTwelveHourMode() {
        val rig = SleepDimRig(is24Hour = false)
        rig.advanceTo(0L)
        rig.render()
        assertEquals("7:42:07 PM", rig.view.clockTimeText)
    }

    @Test
    fun theNormalClockChangesWhenTheSecondChanges() {
        val rig = SleepDimRig()
        rig.advanceTo(0L)
        val first = rig.render()
        assertEquals("19:42:07", rig.view.clockTimeText)
        val firstText = rig.view.clockTimeText

        // Still inside the same second (07.650): the picture and the cached string do not change.
        rig.wallMillis += 400L
        val sameSecond = rig.render()
        assertSame("the text is cached by the second, not rebuilt per frame", firstText, rig.view.clockTimeText)
        assertTrue("same second, same pixels", first.sameAs(sameSecond))

        // One second on (08.650): only the time text differs.
        rig.wallMillis += 1000L
        val nextSecond = rig.render()
        assertEquals("19:42:08", rig.view.clockTimeText)
        assertFalse("the clock region must change", first.sameAs(nextSecond))
        val changed = SleepDimRig.diffBounds(first, nextSecond)
        assertNotNull(changed)
        assertTrue("only the time text changes, but the change spans ${changed!!.toList()}", inside(changed, timeRegion))
    }

    @Test
    fun theSecondsCarryIntoTheMinuteAndHour() {
        val rig = SleepDimRig()
        rig.wallMillis = wallAt(19, 59, 59, 900)
        rig.render()
        assertEquals("19:59:59", rig.view.clockTimeText)
        rig.wallMillis += 200L
        rig.render()
        assertEquals("20:00:00", rig.view.clockTimeText)
    }

    @Test
    fun theTimeStaysInsideTheSafeAreaInBothModes() {
        // "12:59:59 PM" is the widest string; also try midnight and the single-digit hours.
        val cases = listOf(
            true to wallAt(23, 59, 59),
            true to wallAt(0, 0, 0),
            true to wallAt(12, 0, 0),
            false to wallAt(12, 59, 59),
            false to wallAt(10, 59, 59),
            false to wallAt(8, 8, 8),
            false to wallAt(0, 0, 0),
        )
        for ((is24, wall) in cases) {
            val rig = SleepDimRig(is24Hour = is24, wallMillis = wall)
            rig.advanceTo(0L)
            val frame = rig.render()
            // Ink is near white; no dot colour reaches 225 in all three channels, so this box is the time text.
            val box = SleepDimRig.brightBounds(frame, 225)
            assertNotNull("no time text in ${rig.view.clockTimeText}", box)
            val label = "'${rig.view.clockTimeText}' box ${box!!.toList()}"
            val safeLeft = (rig.width * 0.05f).toInt()
            val safeRight = rig.width - safeLeft
            val safeTop = (rig.height * 0.05f).toInt()
            val safeBottom = rig.height - safeTop
            assertTrue("$label starts left of the safe area", box[0] >= safeLeft)
            assertTrue("$label ends right of the safe area", box[2] <= safeRight)
            assertTrue("$label starts above the safe area", box[1] >= safeTop)
            assertTrue("$label ends below the safe area", box[3] <= safeBottom)
        }
    }

    @Test
    fun digitsAreTheSameWidthSoTheSecondsDoNotShiftTheText() {
        // The dream draws in Plex Mono; the system monospace is the fallback if the font fails to load.
        val plexMono = Typeface.createFromFile(File("src/main/res/font/plex_mono_regular.ttf"))
        for (typeface in listOf(plexMono, Typeface.MONOSPACE)) {
            val paint = Paint().apply {
                this.typeface = typeface
                textSize = 80f
            }
            val zero = paint.measureText("0")
            assertTrue(zero > 0f)
            for (d in '1'..'9') {
                assertEquals("digit $d", zero, paint.measureText(d.toString()), 0.01f)
            }
            val reference = paint.measureText("00:00:00")
            for (sample in listOf("11:11:11", "88:88:88", "19:42:07", "23:59:59", "01:23:45")) {
                assertEquals(sample, reference, paint.measureText(sample), 0.01f)
            }
            assertEquals(paint.measureText("00:00:00 AM"), paint.measureText("12:59:59 PM"), 0.01f)
        }
    }

    // ---- Reduced motion: a Handler repaints the clock once a second ----------------------------------------

    @Test
    fun reducedMotionRepaintsRightAfterEveryWholeSecondAndNotBefore() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.start() // the rig stopped it; the dream calls start() when dreaming begins
        shadowOf(Looper.getMainLooper()).idle()
        rig.render()
        assertEquals("19:42:07", rig.view.clockTimeText)

        // The rig's wall clock moves with the looper and starts 250 ms into second 07, so the next whole second
        // is 750 ms away and the timer is aimed a few ms (the slack) past it. A fixed one-second delay would
        // fire at 1000 ms and fail the first check below.
        val slack = OxyfieldClockText.TICK_SLACK_MILLIS
        var elapsed = 0L
        fun advanceTo(millis: Long) {
            idleFor(millis - elapsed)
            elapsed = millis
        }
        rig.clearInvalidated()
        advanceTo(749L)
        assertFalse("no repaint 1 ms before the whole second", rig.wasInvalidated())
        advanceTo(750L + slack + 1L)
        assertTrue("repainted right after the whole second", rig.wasInvalidated())
        rig.render()
        assertEquals("19:42:08", rig.view.clockTimeText)

        // And again for the next several seconds: nothing until the boundary, a repaint just after it, and the
        // text shown is always the rig clock's current second (no drift, no skipped or repeated second).
        for (k in 1..5) {
            val boundary = 750L + 1000L * k
            rig.clearInvalidated()
            advanceTo(boundary - 1L)
            assertFalse("no repaint 1 ms before second ${8 + k}", rig.wasInvalidated())
            advanceTo(boundary + slack + 1L)
            assertTrue("repaint right after second ${8 + k}", rig.wasInvalidated())
            rig.render()
            assertEquals("19:42:%02d".format(8 + k), rig.view.clockTimeText)
            assertEquals(rig.expectedTimeText(), rig.view.clockTimeText)
        }
    }

    @Test
    fun reducedMotionStillShowsTheDotsFrameUnchangedWhileTheSecondsTick() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.start()
        val first = rig.render()
        idleFor(1000L) // the rig clock moves on with the looper: 08.250
        val second = rig.render()
        assertEquals("19:42:08", rig.view.clockTimeText)
        val changed = SleepDimRig.diffBounds(first, second)
        assertNotNull(changed)
        assertTrue("only the time text changes in reduced motion, changed ${changed!!.toList()}", inside(changed, timeRegion))
    }

    @Test
    fun theSecondTickerStopsWithStopAndComesBackWithStart() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.start()
        idleFor(1000L)
        rig.view.stop()
        rig.clearInvalidated()
        idleFor(5000L)
        assertFalse("nothing repaints after stop()", rig.wasInvalidated())

        rig.view.start()
        rig.clearInvalidated()
        idleFor(1100L)
        assertTrue("start() brings the ticker back", rig.wasInvalidated())
    }

    @Test
    fun theSecondTickerAndTheReceiverAreGoneAfterDetach() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.start()
        idleFor(1000L)
        (rig.view.parent as ViewGroup).removeView(rig.view)
        shadowOf(Looper.getMainLooper()).idle()
        rig.clearInvalidated()
        idleFor(5000L)
        rig.minuteTick()
        rig.broadcast(Intent.ACTION_TIME_CHANGED)
        rig.broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        assertFalse("a detached view never repaints", rig.wasInvalidated())
    }

    @Test
    fun aHiddenWindowStopsTheTickerAndAShownOneRestartsIt() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.start()
        idleFor(1000L)
        rig.view.dispatchWindowVisibilityChanged(View.GONE)
        rig.clearInvalidated()
        idleFor(5000L)
        rig.minuteTick()
        assertFalse("no repaint while the window is hidden", rig.wasInvalidated())

        rig.view.dispatchWindowVisibilityChanged(View.VISIBLE)
        rig.clearInvalidated()
        idleFor(1100L)
        assertTrue("and it ticks again once shown", rig.wasInvalidated())
    }

    @Test
    fun reducedMotionWithoutSleepModeIsUnchanged() {
        val rig = SleepDimRig(reducedMotion = true)
        val frame = rig.render()
        assertEquals(rig.palette.bg, frame.getPixel(3, 3))
        assertTrue(rig.timeBrightness(frame) >= 220)
    }

    // ---- Sleep mode ------------------------------------------------------------------------------------------

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
    fun theClockFadesToItsFaintFloorBeforeTheFadeEnds() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(0L)
        val start = rig.render()
        assertTrue("clock visible at 0 s", rig.timeBrightness(start) >= 220)
        assertTrue("and at full ink", rig.timePixelsAt(start, 1f) > 300)

        // Mid-fade the text is drawn at exactly the clock level the maths gives for that moment.
        rig.advanceTo(10_000L)
        val tenSeconds = rig.render()
        val midLevel = SleepDimMath.clockLevel(10_000L, reducedMotion = false)
        assertTrue("0 < $midLevel < 1", midLevel < 1f && midLevel > SleepDimMath.CLOCK_FLOOR)
        assertTrue("clock at the mid level", rig.timePixelsAt(tenSeconds, midLevel) > 300)

        // 60% of 45 s = 27 s. By 28 s the clock is on its floor, though the fade (and the dots) are still going.
        rig.advanceTo(28_000L)
        val late = rig.render()
        assertFalse(rig.view.isSleepSettled)
        assertTrue("clock at its floor by 28 s", rig.timePixelsAt(late, SleepDimMath.CLOCK_FLOOR) > 300)
        assertTrue("the date line too", rig.datePixelsAt(late, SleepDimMath.CLOCK_FLOOR) > 30)
        assertEquals("nothing of the clock is at full ink any more", 0, rig.timePixelsAt(late, 1f))

        // And it stays there for the rest of the fade. (A glyph's anti-aliased edge blends with the dot under it, and
        // the dots move, so an edge pixel can land on the exact colour in one frame and not in another: a few differ.)
        rig.advanceTo(44_000L)
        val almost = rig.render()
        val atLate = rig.timePixelsAt(late, SleepDimMath.CLOCK_FLOOR)
        val atAlmost = rig.timePixelsAt(almost, SleepDimMath.CLOCK_FLOOR)
        assertTrue("$atLate pixels at 28 s, $atAlmost at 44 s", kotlin.math.abs(atLate - atAlmost) <= 20)
    }

    @Test
    fun theSettledFrameKeepsAFaintClock() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(0L)
        val start = rig.render()
        rig.advanceTo(60_000L)
        assertTrue(rig.view.isSleepSettled)
        val settled = rig.render()

        val clockBrightness = rig.timeBrightness(settled)
        val around = rig.aroundBrightness(settled)
        // About 35/255: ink at 15%.
        assertTrue("faint clock, brightest clock pixel was $clockBrightness", clockBrightness in 25..45)
        assertTrue("brighter than the dots around it ($around)", clockBrightness > around + 10)
        assertTrue("dimmer than at t=0", clockBrightness < rig.timeBrightness(start) - 150)
        assertTrue("time text is there", rig.timePixelsAt(settled, SleepDimMath.CLOCK_FLOOR) > 300)
        assertTrue("date line is there", rig.datePixelsAt(settled, SleepDimMath.CLOCK_FLOOR) > 30)
    }

    @Test
    fun theSleepClockHasNoSeconds() {
        val rig = SleepDimRig(is24Hour = true)
        rig.render()
        assertEquals("the normal screensaver shows seconds", "19:42:07", rig.view.clockTimeText)

        // From the moment the dim starts the text is the minute only, through the fade and once settled.
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(0L)
        rig.render()
        assertEquals("19:42", rig.view.clockTimeText)
        rig.advanceTo(20_000L)
        rig.render()
        assertEquals("19:42", rig.view.clockTimeText)
        rig.advanceTo(60_000L)
        val a = rig.render()
        assertEquals("19:42", rig.view.clockTimeText)

        // A second later in the same minute: not a pixel changes.
        rig.wallMillis += 1000L
        val b = rig.render()
        assertEquals("19:42", rig.view.clockTimeText)
        assertTrue("a second ticking must not change the sleep screen", a.sameAs(b))

        // Leaving sleep mode brings the seconds back.
        rig.view.clearSleepDim()
        rig.render()
        assertEquals("19:42:08", rig.view.clockTimeText)
    }

    @Test
    fun theSleepClockHasNoSecondsInTwelveHourMode() {
        val rig = SleepDimRig(is24Hour = false)
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(60_000L)
        rig.render()
        assertEquals("7:42 PM", rig.view.clockTimeText)
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
    fun twoSettledFramesInTheSameMinuteAreByteIdenticalEvenAfterATick() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(60_000L)
        val first = rig.render()

        // 19:42:07 -> 19:42:40: same minute, with the system's minute tick for good measure (it can arrive at
        // other moments too, e.g. a time-changed broadcast).
        rig.wallMillis += 33_000L
        rig.minuteTick()
        val second = rig.render()
        assertEquals("19:42", rig.view.clockTimeText)
        assertTrue("same minute, same pixels", first.sameAs(second))
    }

    @Test
    fun aSettledFrameRepaintsWhenTheMinuteChangesAndOnlyTheClockDiffers() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(60_000L)
        val before = rig.render()
        assertEquals("19:42", rig.view.clockTimeText)

        // Nothing at all repaints on its own while the minute stays the same: the frame loop is stopped.
        rig.clearInvalidated()
        idleFor(30_000L)
        assertFalse("a settled frame is not repainted without a reason", rig.wasInvalidated())

        // The minute changes and the system ticks: the view asks for exactly one more draw.
        rig.wallMillis = wallAt(19, 43, 0, 20)
        rig.clearInvalidated()
        rig.minuteTick()
        assertTrue("the minute tick must repaint the settled frame", rig.wasInvalidated())
        val after = rig.render()
        assertEquals("19:43", rig.view.clockTimeText)

        assertFalse("the new minute must show", before.sameAs(after))
        val changed = SleepDimRig.diffBounds(before, after)
        assertNotNull(changed)
        assertTrue("only the time text may change, changed ${changed!!.toList()}", inside(changed, timeRegion))
        // Still faint, still at the floor.
        assertTrue(rig.timePixelsAt(after, SleepDimMath.CLOCK_FLOOR) > 300)
        // The frame loop did not wake up: the view is settled and still reports so.
        assertTrue(rig.view.isSleepSettled)
    }

    @Test
    fun aTickThatLandsJustBeforeTheMinuteIsCheckedAgainAfterIt() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(60_000L)
        rig.render()

        // The tick arrives 200 ms early: drawing now would still say 19:42.
        rig.wallMillis = wallAt(19, 42, 59, 800)
        rig.minuteTick()
        rig.render()
        assertEquals("19:42", rig.view.clockTimeText)

        // The wall clock reaches 19:43:00.010; the view looks again by itself.
        rig.wallMillis = wallAt(19, 43, 0, 10)
        rig.clearInvalidated()
        idleFor(300L)
        assertTrue("an early tick must be rechecked", rig.wasInvalidated())
        rig.render()
        assertEquals("19:43", rig.view.clockTimeText)
    }

    @Test
    fun theSleepScreenReceivesTheMinuteTickWhetherOrNotMotionIsOn() {
        for (reduced in listOf(false, true)) {
            val rig = SleepDimRig(reducedMotion = reduced)
            rig.view.startSleepDim(rig.startMillis)
            if (reduced) rig.view.start() // the rig drives frames by hand otherwise
            rig.advanceTo(60_000L) // a no-op in reduced motion
            assertTrue(rig.view.isSleepSettled)
            rig.render()

            rig.wallMillis += 60_000L
            rig.clearInvalidated()
            rig.minuteTick()
            assertTrue("repaint on the minute tick (reducedMotion=$reduced)", rig.wasInvalidated())
            rig.render()
            assertEquals("19:43", rig.view.clockTimeText)
        }
    }

    @Test
    fun theSleepScreenDoesNotRepaintEverySecondInReducedMotion() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.startSleepDim(rig.startMillis)
        rig.view.start()
        rig.render()
        rig.clearInvalidated()
        idleFor(5000L)
        assertFalse("seconds are not shown on the sleep screen, so nothing ticks", rig.wasInvalidated())
    }

    @Test
    fun leavingSleepModeInReducedMotionBringsTheSecondsBack() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.startSleepDim(rig.startMillis)
        rig.view.start()
        rig.render()
        assertEquals("19:42", rig.view.clockTimeText)

        rig.view.clearSleepDim()
        rig.clearInvalidated()
        idleFor(1100L) // 07.250 -> 08.350 on the rig clock: the ticker fired at the whole second
        assertTrue(rig.wasInvalidated())
        rig.render()
        assertEquals("19:42:08", rig.view.clockTimeText)
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
        // The clock is deliberately brighter than the dots (about 35 against 11 at most), so leave out just the two
        // text lines, each at its measured bounds plus a small margin; a dot anywhere else is still checked.
        val timeBox = SleepDimRig.brightBounds(frame, 20, within = timeRegion)
        val dateBox = SleepDimRig.brightBounds(frame, 20, within = dateRegion)
        assertNotNull("time text at the floor", timeBox)
        assertNotNull("date text at the floor", dateBox)
        val margin = 4
        val excluded = listOf(timeBox!!, dateBox!!).map {
            intArrayOf(it[0] - margin, it[1] - margin, it[2] + margin, it[3] + margin)
        }
        val excludedArea = excluded.sumOf { (it[2] - it[0]) * (it[3] - it[1]) }
        val oldRectArea = (clockRegion[2] - clockRegion[0]) * (clockRegion[3] - clockRegion[1])
        assertTrue("the excluded area ($excludedArea px) is well under the old clock rectangle ($oldRectArea px)", excludedArea < oldRectArea / 2)
        assertEquals(0, SleepDimRig.countAbove(frame, 15, exclude = excluded))
        // Without the exclusion the clock itself is of course found.
        assertTrue(SleepDimRig.countAbove(frame, 15) > 300)
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
        assertTrue(rig.timeBrightness(frame) >= 220)
    }

    @Test
    fun reducedMotionShowsTheDimStillFrameAtOnceWithAFaintClock() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.startSleepDim(rig.startMillis)
        assertTrue(rig.view.isSleepSettled)
        val frame = rig.render()
        assertEquals(SleepDimMath.scaleColor(rig.palette.bg, SleepDimMath.FLOOR), frame.getPixel(3, 3))
        assertTrue("faint clock", rig.timePixelsAt(frame, SleepDimMath.CLOCK_FLOOR) > 300)
        assertTrue("date line", rig.datePixelsAt(frame, SleepDimMath.CLOCK_FLOOR) > 30)
        assertTrue(rig.timeBrightness(frame) in 25..45)
        assertTrue(SleepDimRig.meanLevel(frame) < 6.0)
        assertEquals("19:42", rig.view.clockTimeText)
    }

    // ---- Motion mode: the Choreographer frame loop (started for real, frames run by the looper) ----------------
    //
    // Robolectric draws every frame a running loop invalidates, so these tests only let the loop run for a few frames
    // of looper time. The 45 s fade is jumped by hand (advanceTo); the loop's own next frame then sees it settled.

    /** The loop is running, the fade is over (by hand), and one real frame has stopped the loop as it settles. */
    private fun SleepDimRig.settleWhileTheLoopRuns() {
        view.start()
        view.startSleepDim(startMillis)
        advanceTo(45_000L)
        assertTrue("settled by hand", view.isSleepSettled)
        idleFor(200L) // the loop's next real frame finds the fade settled and stops itself
    }

    @Test
    fun theFrameLoopRepaintsWhileStartedAndStopsWithStop() {
        val rig = SleepDimRig()
        // (Robolectric's fake vsync advances the clock for every frame the loop re-posts, so a live loop cannot be run
        // for a stretch of looper time; the settle tests below run exactly one real frame instead.)
        rig.clearInvalidated()
        rig.view.start()
        assertTrue("start() repaints at once", rig.wasInvalidated())

        rig.view.stop()
        rig.clearInvalidated()
        idleFor(2000L)
        assertFalse("and stops with stop()", rig.wasInvalidated())
    }

    @Test
    fun theFrameLoopStopsOnceTheFadeSettlesAndAMinuteTickRepaintsWithoutRestartingIt() {
        val rig = SleepDimRig()
        rig.settleWhileTheLoopRuns()
        rig.render()

        // Settled: the loop has stopped by itself, so nothing repaints, however long it is left.
        rig.clearInvalidated()
        idleFor(5000L)
        assertFalse("no frames once settled", rig.wasInvalidated())

        // The minute ticks: one repaint, and the loop stays stopped.
        rig.wallMillis += 60_000L
        rig.clearInvalidated()
        rig.minuteTick()
        assertTrue("the minute tick repaints a settled frame", rig.wasInvalidated())
        rig.render()
        assertEquals(rig.expectedTimeText(withSeconds = false), rig.view.clockTimeText)
        rig.clearInvalidated()
        idleFor(5000L)
        assertFalse("the tick must not restart the loop", rig.wasInvalidated())
        assertTrue(rig.view.isSleepSettled)
    }

    @Test
    fun startingAgainAfterTheFadeSettledOnlyRepaintsOnce() {
        val rig = SleepDimRig()
        rig.settleWhileTheLoopRuns()
        rig.clearInvalidated()
        idleFor(2000L)
        assertFalse(rig.wasInvalidated())

        // The host calls start() again (e.g. the window comes back): a settled frame is repainted once, not animated.
        rig.view.start()
        assertTrue("start() repaints the settled frame", rig.wasInvalidated())
        rig.clearInvalidated()
        idleFor(2000L)
        assertFalse("but does not start the loop", rig.wasInvalidated())
        assertTrue(rig.view.isSleepSettled)
    }

    @Test
    fun leavingSleepModeBringsTheFrameLoopAndTheSecondsBack() {
        val rig = SleepDimRig()
        rig.settleWhileTheLoopRuns()
        rig.render()
        assertEquals("the sleep clock has no seconds", 5, rig.view.clockTimeText.length)

        rig.view.clearSleepDim()
        assertFalse(rig.view.isSleepDimming)
        assertFalse(rig.view.isSleepSettled)
        rig.clearInvalidated()
        rig.view.start() // as the doc says: start() again to animate after a settled fade
        assertTrue("start() repaints at once and the loop is armed", rig.wasInvalidated())
        rig.view.stop() // render() idles the looper, which would chase a live loop's frames for ever
        val frame = rig.render()
        assertEquals(rig.palette.bg, frame.getPixel(3, 3))
        assertTrue("full ink again", rig.timeBrightness(frame) >= 220)
        assertEquals("and the seconds are back", rig.expectedTimeText(), rig.view.clockTimeText)
        assertEquals(8, rig.view.clockTimeText.length)
        rig.view.stop()
    }

    // ---- System time changes: the cached clock text is rebuilt ---------------------------------------------

    @Test
    fun aTimeChangeRepaintsAStillScreenWithTheNewTime() {
        val rig = SleepDimRig()
        rig.view.startSleepDim(rig.startMillis)
        rig.advanceTo(60_000L)
        rig.render()
        assertEquals("19:42", rig.view.clockTimeText)

        // The user sets the clock three hours on. The frame loop is stopped, so only the broadcast can repaint.
        rig.wallMillis += 3L * 60L * 60L * 1000L
        rig.clearInvalidated()
        rig.broadcast(Intent.ACTION_TIME_CHANGED)
        assertTrue("ACTION_TIME_CHANGED must repaint a settled frame", rig.wasInvalidated())
        rig.render()
        assertEquals("22:42", rig.view.clockTimeText)
    }

    @Test
    fun aTimeZoneChangeRebuildsTheClockTextEvenThoughTheInstantIsTheSame() {
        val saved = TimeZone.getDefault()
        try {
            val rig = SleepDimRig()
            rig.view.startSleepDim(rig.startMillis)
            rig.advanceTo(60_000L)
            rig.render()
            val before = rig.view.clockTimeText
            assertEquals("19:42", before)

            val other = TimeZone.getTimeZone(if (saved.id == "Asia/Kolkata") "America/Los_Angeles" else "Asia/Kolkata")
            TimeZone.setDefault(other)
            rig.clearInvalidated()
            rig.broadcast(Intent.ACTION_TIMEZONE_CHANGED)
            assertTrue("ACTION_TIMEZONE_CHANGED must repaint a settled frame", rig.wasInvalidated())
            rig.render()

            val cal = Calendar.getInstance(other).apply { timeInMillis = rig.wallMillis }
            val expected = OxyfieldClockText.time(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true)
            assertEquals("the clock reads the new zone", expected, rig.view.clockTimeText)
            assertNotEquals("a different zone, a different hour", before, rig.view.clockTimeText)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun aTimeChangeInReducedMotionShowsTheNewTimeAtTheNextSecond() {
        val rig = SleepDimRig(reducedMotion = true)
        rig.view.start()
        rig.render()
        rig.wallMillis = wallAt(8, 5, 30, 100)
        rig.clearInvalidated()
        rig.broadcast(Intent.ACTION_TIME_CHANGED)
        assertTrue("repainted at once, not at the next tick", rig.wasInvalidated())
        rig.render()
        assertEquals("08:05:30", rig.view.clockTimeText)
    }
}

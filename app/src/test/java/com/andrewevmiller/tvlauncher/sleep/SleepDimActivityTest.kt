// purecheck:skip (Robolectric: hosts the Android OxyfieldView)
package com.andrewevmiller.tvlauncher.sleep

import android.content.Intent
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import com.andrewevmiller.tvlauncher.ambient.OxyfieldDreamService
import com.andrewevmiller.tvlauncher.ambient.OxyfieldView
import com.andrewevmiller.tvlauncher.ambient.ReducedMotionProvider
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.NoOpFieldSound
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SleepDimActivityTest {
    private val wakes = AtomicInteger()
    private var previousProvider: ReducedMotionProvider? = null

    @Before
    fun setUp() {
        SleepDimHandshake.reset()
        SleepDimActivity.soundOverride = {
            object : FieldSound by NoOpFieldSound {
                override fun wake() {
                    wakes.incrementAndGet()
                }
            }
        }
        previousProvider = OxyfieldDreamService.reducedMotionProvider
        reducedMotion(false)
    }

    @After
    fun tearDown() {
        SleepDimActivity.soundOverride = null
        OxyfieldDreamService.reducedMotionProvider = previousProvider
        SleepDimHandshake.reset()
    }

    private fun reducedMotion(reduced: Boolean) {
        OxyfieldDreamService.reducedMotionProvider = ReducedMotionProvider { reduced }
    }

    /**
     * Created, started and resumed, but not made visible: ActivityController.visible() attaches the window, which here
     * also runs the main looper ahead to its last scheduled task (the 30 minute safety net would fire at once).
     */
    private fun start(): ActivityController<SleepDimActivity> =
        Robolectric.buildActivity(SleepDimActivity::class.java).create().start().postCreate(null).resume()

    private fun ActivityController<SleepDimActivity>.view(): OxyfieldView? {
        val content = get().findViewById<ViewGroup>(android.R.id.content)
        return if (content.childCount > 0) content.getChildAt(0) as? OxyfieldView else null
    }

    private fun ActivityController<SleepDimActivity>.press(code: Int = KeyEvent.KEYCODE_DPAD_CENTER): Boolean =
        get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))

    private fun idle(millis: Long) = ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)

    // ---- coming up --------------------------------------------------------------------------------------

    @Test
    fun startsWithTheOxyfieldViewInSleepMode() {
        val controller = start()
        val view = controller.view()
        assertNotNull(view)
        assertTrue(view!!.isSleepDimming)
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun tellsTheWorkerItWasCreated() {
        SleepDimHandshake.request(0)
        assertFalse(SleepDimHandshake.isShown())
        start()
        assertTrue(SleepDimHandshake.isShown())
    }

    @Test
    fun keepsTheScreenOn() {
        val flags = start().get().window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun aStartTheWorkerGaveUpOnClosesAtOnce() {
        SleepDimHandshake.request(0)
        SleepDimHandshake.abandon()
        val controller = Robolectric.buildActivity(SleepDimActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        assertNull(controller.view())
        assertFalse(SleepDimHandshake.isShown())
    }

    // ---- reduced motion ---------------------------------------------------------------------------------

    @Test
    fun withReducedMotionTheDimFrameIsStillAtOnce() {
        reducedMotion(true)
        val view = start().view()!!
        assertTrue(view.isSleepDimming)
        assertTrue("reduced motion snaps to the dim still frame", view.isSleepSettled)
    }

    @Test
    fun withoutReducedMotionTheFadeHasNotSettledYet() {
        assertFalse(start().view()!!.isSleepSettled)
    }

    // ---- keys -------------------------------------------------------------------------------------------

    @Test
    fun aKeyInTheFirstSecondIsIgnored() {
        val controller = start()
        controller.press()
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS - 100)
        controller.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse(controller.get().isFinishing)
        assertEquals(0, wakes.get())
        assertTrue(controller.view()!!.isSleepDimming)
    }

    @Test
    fun aLaterKeyWakesPlaysTheWakeSoundAndFinishes() {
        val controller = start()
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
        controller.press()
        assertTrue(controller.get().isFinishing)
        assertEquals(1, wakes.get())
    }

    @Test
    fun anyKeyWakesNotJustArrows() {
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_1)) {
            wakes.set(0)
            val controller = start()
            idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
            controller.press(code)
            assertTrue("key $code", controller.get().isFinishing)
            assertEquals("key $code", 1, wakes.get())
        }
    }

    @Test
    fun theWakeSoundPlaysOnlyOnceForSeveralKeys() {
        val controller = start()
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
        controller.press()
        controller.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, wakes.get())
    }

    @Test
    fun theAutoRepeatOfAKeyThatWasAlreadyDownDoesNotWake() {
        val controller = start()
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
        val repeat = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 1)
        controller.get().dispatchKeyEvent(repeat)
        assertFalse(controller.get().isFinishing)
        assertEquals(0, wakes.get())
    }

    @Test
    fun theVolumeKeysWakeButStayForTheSystemToHandle() {
        val controller = start()
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
        val handled = controller.get().onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse("the system still gets the volume key", handled)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun aSecondStartRestartsTheKeyGrace() {
        val controller = start()
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
        controller.newIntent(Intent())
        controller.press()
        assertFalse(controller.get().isFinishing)
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
        controller.press()
        assertTrue(controller.get().isFinishing)
    }

    // ---- going away -------------------------------------------------------------------------------------

    @Test
    fun finishesByItselfAfterThirtyMinutes() {
        reducedMotion(true) // no frame loop to run through half an hour of virtual time
        val controller = start()
        idle(SleepConfig.DIM_AUTO_FINISH_MILLIS - 1_000)
        assertFalse(controller.get().isFinishing)
        idle(2_000)
        assertTrue(controller.get().isFinishing)
        assertEquals("the safety net is silent: it is not a wake", 0, wakes.get())
    }

    @Test
    fun pressingHomeClosesItToo() {
        val controller = start()
        controller.pause().stop()
        assertTrue(controller.get().isFinishing)
        assertEquals(0, wakes.get())
    }

    @Test
    fun leavesTheViewInNormalModeOnlyOnceTheWindowIsGone() {
        val controller = start()
        val view = controller.view()!!
        idle(SleepConfig.DIM_KEY_GRACE_MILLIS + 100)
        controller.press()
        assertTrue("still dimmed while the window animates out, so no bright flash", view.isSleepDimming)
        controller.pause().stop().destroy()
        assertFalse(view.isSleepDimming)
    }
}

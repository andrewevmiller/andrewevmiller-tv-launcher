// purecheck:skip (Robolectric: BroadcastReceiver, Application)
package com.andrewevmiller.tvlauncher.sleep

import android.content.Context
import android.content.Intent
import com.andrewevmiller.tvlauncher.FieldApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The receiver's body runs through [AlarmHooks], so these tests run the worker inline, fire the watchdog by hand and
 * count how often the PendingResult would have been finished.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepAlarmReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val minute = 60_000L
    private var now = 1_700_000_000_000L

    private fun fire() = Intent(context, SleepAlarmReceiver::class.java).setAction(SleepAlarmScheduler.ACTION_FIRE)

    /** A fake platform side for the end sequence: nothing playing, dim screen "created" at once. */
    private class Actions : SleepEndActions {
        val calls = ArrayList<String>()
        var onDimStart: () -> Unit = {}
        override fun isScreenOn(): Boolean = true
        override fun warmUp() { calls.add("warmUp") }
        override fun playClosingSound() { calls.add("sound") }
        override fun sleep(millis: Long) = Unit
        override fun isPlaying(): Boolean = false
        override fun perform(rung: PauseRung) = Unit
        override fun release() = Unit
        override fun startDimActivity() {
            calls.add("startDimActivity")
            onDimStart()
        }
        override fun dimActivityShown(): Boolean {
            calls.add("dimActivityShown")
            return true
        }
        override fun abandonDimActivity() = Unit
        override fun oxyfieldIsActiveScreensaver(): Boolean = false
        override fun requestDreamDim() = Unit
        override fun startScreensaver(): Boolean = false
        override fun dreamDimConsumed(): Boolean = false
        override fun clearDreamDim() = Unit
    }

    private class Hooks(var timer: SystemSleepTimer?) : AlarmHooks {
        var holds = 0
        var finishes = 0
        var timerLookups = 0
        var watchdogDelay: Long? = null
        private var watchdog: (() -> Unit)? = null
        private var worker: Runnable? = null
        var workerStarts = 0
        var startWorkerThrows: Throwable? = null

        /** true: the worker runs as soon as it is started. false: it waits for [runWorker], like a blocked thread. */
        var inline = true

        /** Calls made while the worker was running, to see what the broadcast looked like at that moment. */
        val finishesWhenWorkerCalled = ArrayList<Int>()

        override fun timer(context: Context): SystemSleepTimer? {
            timerLookups++
            return timer
        }

        override fun holdBroadcast(): () -> Unit {
            holds++
            return { finishes++ }
        }

        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            watchdogDelay = delayMillis
            watchdog = action
        }

        override fun startWorker(work: Runnable) {
            startWorkerThrows?.let { throw it }
            workerStarts++
            worker = work
            if (inline) work.run()
        }

        fun runWorker() = worker!!.run()
        fun fireWatchdog() = watchdog!!.invoke()
        val watchdogPosted get() = watchdog != null
    }

    private fun timerWith(actions: () -> SleepEndActions): SystemSleepTimer =
        SystemSleepTimer(context, now = { now }, endActions = actions, settle = {})

    private fun dueTimer(actions: () -> SleepEndActions): SystemSleepTimer {
        val timer = timerWith(actions)
        timer.start(15)
        now = (timer.state.value as SleepTimerState.Running).deadlineAtMillis
        return timer
    }

    // ---- strays return before goAsync -------------------------------------------------------------------

    @Test
    fun anIntentThatIsNotOurAlarmReturnsBeforeAnythingElse() {
        val hooks = Hooks(timerWith { Actions() })
        SleepAlarmReceiver().handle(context, Intent(Intent.ACTION_BOOT_COMPLETED), hooks)
        assertEquals("no timer lookup", 0, hooks.timerLookups)
        assertEquals("goAsync was not called", 0, hooks.holds)
        assertFalse(hooks.watchdogPosted)
        assertEquals("no worker", 0, hooks.workerStarts)
        assertEquals(0, hooks.finishes)
    }

    @Test
    fun anAlarmWithNoSystemTimerReturnsBeforeGoAsync() {
        val hooks = Hooks(timer = null)
        SleepAlarmReceiver().handle(context, fire(), hooks)
        assertEquals(1, hooks.timerLookups)
        assertEquals(0, hooks.holds)
        assertEquals(0, hooks.workerStarts)
    }

    // ---- the pending result is finished exactly once ----------------------------------------------------

    @Test
    fun theNormalPathFinishesTheBroadcastExactlyOnceRightAfterTheDimRequest() {
        val actions = Actions()
        val timer = dueTimer { actions }
        val hooks = Hooks(timer)
        // When the dim screen is asked for, the broadcast must still be open; once it is asked for, it is released.
        actions.onDimStart = { hooks.finishesWhenWorkerCalled.add(hooks.finishes) }

        SleepAlarmReceiver().handle(context, fire(), hooks)

        assertEquals(1, hooks.holds)
        assertEquals(1, hooks.workerStarts)
        assertEquals(listOf(0), hooks.finishesWhenWorkerCalled)
        assertEquals("finished once, by the dim request; the finally and the watchdog add nothing", 1, hooks.finishes)
        assertEquals(listOf("warmUp", "sound", "startDimActivity", "dimActivityShown"), actions.calls)
        assertEquals(SleepTimerState.Idle, timer.state.value)

        hooks.fireWatchdog() // arrives later: still just the one finish
        assertEquals(1, hooks.finishes)
    }

    @Test
    fun theWatchdogIsPostedAtTheDocumentedDelay() {
        val hooks = Hooks(dueTimer { Actions() })
        SleepAlarmReceiver().handle(context, fire(), hooks)
        assertEquals(SleepConfig.RELEASE_WATCHDOG_MILLIS, hooks.watchdogDelay)
        assertEquals(8_500L, hooks.watchdogDelay)
    }

    @Test
    fun nothingDueStillFinishesTheBroadcastOnceAndBuildsNothing() {
        val built = java.util.concurrent.atomic.AtomicInteger()
        val timer = timerWith { built.incrementAndGet(); Actions() }
        timer.start(30)
        val hooks = Hooks(timer)

        SleepAlarmReceiver().handle(context, fire(), hooks)

        assertEquals(1, hooks.holds)
        assertEquals(1, hooks.finishes)
        assertEquals(0, built.get())
        assertEquals(30 * minute, (timer.state.value as SleepTimerState.Running).totalMillis)
    }

    @Test
    fun anErrorEscapingTheWorkerStillFinishesTheBroadcastOnce() {
        // An Error is not caught by the sequence's own guards; it reaches the worker's catch-all.
        val timer = dueTimer { throw LinkageError("test") }
        val hooks = Hooks(timer)

        SleepAlarmReceiver().handle(context, fire(), hooks)

        assertEquals(1, hooks.finishes)
        assertEquals("and the timer is not left firing", SleepTimerState.Idle, timer.state.value)
        hooks.fireWatchdog()
        assertEquals(1, hooks.finishes)
    }

    @Test
    fun aWorkerThatCannotBeStartedFinishesTheBroadcastOnce() {
        val hooks = Hooks(dueTimer { Actions() }).apply { startWorkerThrows = IllegalStateException("no thread") }

        SleepAlarmReceiver().handle(context, fire(), hooks)

        assertEquals(1, hooks.holds)
        assertEquals(1, hooks.finishes)
        hooks.fireWatchdog()
        assertEquals(1, hooks.finishes)
    }

    @Test
    fun theWatchdogFinishesTheBroadcastWhileTheWorkerIsStillBusy() {
        val actions = Actions()
        val timer = dueTimer { actions }
        val hooks = Hooks(timer).apply { inline = false }

        SleepAlarmReceiver().handle(context, fire(), hooks)
        assertEquals("the worker has not run: the broadcast is held open", 0, hooks.finishes)

        hooks.fireWatchdog()
        assertEquals(1, hooks.finishes)

        // The worker catches up later: its own releases add nothing.
        hooks.runWorker()
        assertEquals(1, hooks.finishes)
        assertTrue(actions.calls.contains("startDimActivity"))
        assertEquals(SleepTimerState.Idle, timer.state.value)
    }

    // ---- the real hooks ---------------------------------------------------------------------------------

    private fun logged(fragment: String, timeoutMillis: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (ShadowLog.getLogsForTag(SleepConfig.TAG).any { it.msg?.contains(fragment) == true }) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun theContainerHandsOutTheSystemTimer() {
        val container = (context as FieldApplication).container
        assertNotNull(container.sleepTimer as? SystemSleepTimer)
        assertEquals(SleepTimerState.Idle, container.sleepTimer.state.value)
    }

    @Test
    fun theRealReceiverOnAStrayAlarmRunsItsWorkerAndLeavesTheTimerAlone() {
        val container = (context as FieldApplication).container
        container.sleepTimer.start(30)
        val before = container.sleepTimer.state.value

        // goAsync() is null outside a real delivery; the receiver copes with that. The real worker thread logs that
        // it found nothing due: waiting for that line is waiting for a condition, not for a fixed time.
        SleepAlarmReceiver().onReceive(context, fire())

        assertTrue("the worker ran and found nothing due", logged("no due timer"))
        assertEquals(before, container.sleepTimer.state.value)
    }
}

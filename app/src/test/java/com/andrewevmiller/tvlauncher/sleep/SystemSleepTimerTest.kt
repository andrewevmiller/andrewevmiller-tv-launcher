// purecheck:skip (Robolectric: AlarmManager, SharedPreferences)
package com.andrewevmiller.tvlauncher.sleep

import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// ShadowAlarmManager.ScheduledAlarm exposes type, operation and showIntent only as (deprecated) public fields.
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemSleepTimerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val shadowAlarms = shadowOf(alarmManager)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SleepConfig.PREFS_NAME, Context.MODE_PRIVATE)

    private val minute = 60_000L
    private var now = 1_700_000_000_000L

    private fun timer() = SystemSleepTimer(context, now = { now })

    private fun alarmTimes(): List<Long> = shadowAlarms.scheduledAlarms.map { it.triggerAtMs }

    private fun running(timer: SystemSleepTimer) = timer.state.value as SleepTimerState.Running

    // ---- the state flow ---------------------------------------------------------------------------------

    @Test
    fun theStateFlowShowsIdleThenRunningThenIdleAgain() {
        val timer = timer()
        val seen = ArrayList<SleepTimerState>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { timer.state.collect { seen.add(it) } }

        timer.start(30)
        timer.extend()
        timer.cancel()
        job.cancel()

        assertEquals(
            listOf(
                SleepTimerState.Idle,
                SleepTimerState.Running(now + 30 * minute, 30 * minute),
                SleepTimerState.Running(now + 45 * minute, 45 * minute),
                SleepTimerState.Idle,
            ),
            seen,
        )
    }

    @Test
    fun theStateFlowShowsFiringBetweenRunningAndIdleWhenTheTimerEnds() {
        val timer = timer()
        timer.start(15)
        val deadline = running(timer).deadlineAtMillis
        val seen = ArrayList<SleepTimerState>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { timer.state.collect { seen.add(it) } }

        now = deadline
        assertTrue(timer.beginFiring())
        timer.endFiring()
        job.cancel()

        assertEquals(
            listOf(SleepTimerState.Running(deadline, 15 * minute), SleepTimerState.Firing, SleepTimerState.Idle),
            seen,
        )
    }

    // ---- start ------------------------------------------------------------------------------------------

    @Test
    fun startsIdleWithNoAlarm() {
        val timer = timer()
        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun startSchedulesExactlyOneAlarmClockAlarmAtTheDeadline() {
        val timer = timer()
        timer.start(30)

        val state = running(timer)
        assertEquals(now + 30 * minute, state.deadlineAtMillis)
        assertEquals(30 * minute, state.totalMillis)

        val alarms = shadowAlarms.scheduledAlarms
        assertEquals(1, alarms.size)
        val alarm = alarms[0]
        assertEquals(now + 30 * minute, alarm.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        // setAlarmClock, not an inexact alarm: it carries the "show" intent and the clock info.
        assertNotNull(alarm.showIntent)
        assertNotNull(alarm.alarmClockInfo)
        assertEquals(now + 30 * minute, alarm.alarmClockInfo!!.triggerTime)
        assertNotNull("an explicit broadcast", alarm.operation)
    }

    @Test
    fun theAlarmIsAnExplicitBroadcastToTheReceiver() {
        timer().start(15)
        val saved = shadowOf(shadowAlarms.scheduledAlarms[0].operation)
        assertTrue(saved.isBroadcastIntent)
        assertEquals(SleepAlarmReceiver::class.java.name, saved.savedIntent.component?.className)
        assertEquals(SleepAlarmScheduler.ACTION_FIRE, saved.savedIntent.action)
    }

    @Test
    fun aSecondStartReplacesTheFirstAlarm() {
        val timer = timer()
        timer.start(30)
        now += 5 * minute
        timer.start(60)

        assertEquals(listOf(now + 60 * minute), alarmTimes())
        assertEquals(now + 60 * minute, running(timer).deadlineAtMillis)
    }

    @Test
    fun startKeepsTheStateStoredSoItSurvivesAProcessRestart() {
        timer().start(45)
        assertTrue(prefs.contains("deadline_at_millis"))
        assertTrue(prefs.contains("total_millis"))
        assertEquals(now + 45 * minute, prefs.getLong("deadline_at_millis", 0))
        assertEquals(45 * minute, prefs.getLong("total_millis", 0))
    }

    // ---- extend -----------------------------------------------------------------------------------------

    @Test
    fun extendMovesTheDeadlineAndTheAlarm() {
        val timer = timer()
        timer.start(30)
        now += 10 * minute
        timer.extend()

        val state = running(timer)
        assertEquals(now - 10 * minute + 45 * minute, state.deadlineAtMillis)
        assertEquals(45 * minute, state.totalMillis)
        assertEquals(listOf(state.deadlineAtMillis), alarmTimes())
        assertEquals(state.deadlineAtMillis, prefs.getLong("deadline_at_millis", 0))
    }

    @Test
    fun extendNeverGoesPast240MinutesFromNow() {
        val timer = timer()
        timer.start(120)
        repeat(30) { timer.extend() }
        assertEquals(now + SleepPresets.MAX_MINUTES * minute, running(timer).deadlineAtMillis)
        assertEquals(listOf(now + SleepPresets.MAX_MINUTES * minute), alarmTimes())

        // An hour later the cap has moved with "now": one more step fits, then it stops at the new cap.
        val capBefore = now + SleepPresets.MAX_MINUTES * minute
        now += 60 * minute
        timer.extend()
        assertEquals(capBefore + 15 * minute, running(timer).deadlineAtMillis)
        repeat(30) { timer.extend() }
        assertEquals(now + SleepPresets.MAX_MINUTES * minute, running(timer).deadlineAtMillis)
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun extendWhenIdleDoesNothing() {
        val timer = timer()
        timer.extend()
        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        assertFalse(prefs.contains("deadline_at_millis"))
    }

    // ---- cancel -----------------------------------------------------------------------------------------

    @Test
    fun cancelRemovesTheAlarmAndClearsTheStore() {
        val timer = timer()
        timer.start(30)
        timer.cancel()

        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        assertFalse(prefs.contains("deadline_at_millis"))
        assertFalse(prefs.contains("total_millis"))
    }

    @Test
    fun cancelWhenIdleIsHarmless() {
        val timer = timer()
        timer.cancel()
        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun aCancelledTimerStaysCancelledForANewInstance() {
        timer().apply { start(30); cancel() }
        val again = timer()
        assertEquals(SleepTimerState.Idle, again.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
    }

    // ---- restore ----------------------------------------------------------------------------------------

    @Test
    fun aNewInstanceRestoresARunningTimerAndSchedulesItsAlarmAgain() {
        val first = timer()
        first.start(60)
        val deadline = running(first).deadlineAtMillis
        // The process dies; for the test the alarm is cancelled so we can see the new instance set it again.
        SleepAlarmScheduler(context).cancel()
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        now += 20 * minute

        val second = timer()
        assertEquals(SleepTimerState.Running(deadline, 60 * minute), second.state.value)
        assertEquals(listOf(deadline), alarmTimes())
    }

    @Test
    fun aDeadlineLongPastIsDroppedAndTheStoreCleared() {
        timer().start(30)
        now += 30 * minute + SleepConfig.DUE_GRACE_MILLIS + 1

        val second = timer()
        assertEquals(SleepTimerState.Idle, second.state.value)
        assertFalse(prefs.contains("deadline_at_millis"))
        assertFalse(prefs.contains("total_millis"))
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun aTVThatWasOffForHoursNeverFiresLate() {
        timer().start(45)
        now += 8 * 60 * minute
        val second = timer()
        assertEquals(SleepTimerState.Idle, second.state.value)
        assertFalse(second.beginFiring())
    }

    @Test
    fun aCorruptStoreIsDropped() {
        prefs.edit().putLong("deadline_at_millis", now + 20 * minute).putLong("total_millis", -1L).commit()
        val timer = timer()
        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertFalse(prefs.contains("deadline_at_millis"))

        prefs.edit().putLong("deadline_at_millis", now + 20 * 60 * minute).putLong("total_millis", 45 * minute).commit()
        assertEquals(SleepTimerState.Idle, timer().state.value)
        assertFalse(prefs.contains("total_millis"))
    }

    @Test
    fun aHalfStoredTimerIsDropped() {
        prefs.edit().putLong("deadline_at_millis", now + 20 * minute).commit()
        assertEquals(SleepTimerState.Idle, timer().state.value)
        assertFalse(prefs.contains("deadline_at_millis"))
    }

    // ---- the cold-process alarm -------------------------------------------------------------------------

    @Test
    fun aProcessStartedForTheAlarmStillFindsTheTimerDue() {
        val deadline = run {
            val warm = timer()
            warm.start(15)
            running(warm).deadlineAtMillis
        }
        // The launcher process was killed. The alarm fires at the deadline and a new process is started for it,
        // which takes a moment: by then "now" is already past the deadline.
        now = deadline + 800

        val cold = timer()
        assertEquals(SleepTimerState.Running(deadline, 15 * minute), cold.state.value)
        assertTrue(cold.beginFiring())
        assertEquals(SleepTimerState.Firing, cold.state.value)
    }

    @Test
    fun theBackupAlarmOfAJustDueTimerIsCancelledByTheClaim() {
        val deadline = timer().run { start(15); running(this).deadlineAtMillis }
        now = deadline + 300
        val cold = timer()
        // Restoring a just-due timer also sets a back-up alarm a moment ahead.
        assertEquals(listOf(now + SleepConfig.FALLBACK_DELAY_MILLIS), alarmTimes())

        assertTrue(cold.beginFiring())
        assertTrue("claiming must leave no alarm behind", shadowAlarms.scheduledAlarms.isEmpty())
        assertFalse(prefs.contains("deadline_at_millis"))
    }

    // ---- firing -----------------------------------------------------------------------------------------

    @Test
    fun anAlarmBeforeTheDeadlineIsNotDue() {
        val timer = timer()
        timer.start(30)
        now += 10 * minute
        assertFalse(timer.beginFiring())
        assertEquals(30 * minute, running(timer).totalMillis)
        assertEquals("the timer and its alarm are untouched", 1, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun anExtendedTimerIsNotDueWhenTheOldAlarmArrives() {
        val timer = timer()
        timer.start(15)
        val oldDeadline = running(timer).deadlineAtMillis
        timer.extend()
        now = oldDeadline
        assertFalse(timer.beginFiring())
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun aCancelledTimerIsNotDue() {
        val timer = timer()
        timer.start(15)
        val deadline = running(timer).deadlineAtMillis
        timer.cancel()
        now = deadline
        assertFalse(timer.beginFiring())
        assertEquals(SleepTimerState.Idle, timer.state.value)
    }

    @Test
    fun theClaimWorksOnceThenFiringThenIdle() {
        val timer = timer()
        timer.start(15)
        now = running(timer).deadlineAtMillis

        assertTrue(timer.beginFiring())
        assertEquals(SleepTimerState.Firing, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        assertFalse(prefs.contains("deadline_at_millis"))
        assertFalse("a second alarm cannot fire it again", timer.beginFiring())

        timer.endFiring()
        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertFalse(timer.beginFiring())
    }

    @Test
    fun aClaimedTimerIsGoneForANewProcessToo() {
        val timer = timer()
        timer.start(15)
        now = running(timer).deadlineAtMillis
        assertTrue(timer.beginFiring())

        val after = timer()
        assertEquals(SleepTimerState.Idle, after.state.value)
        assertFalse(after.beginFiring())
    }

    @Test
    fun aNewTimerStartedWhileFiringSurvivesTheEndOfTheSequence() {
        val timer = timer()
        timer.start(15)
        now = running(timer).deadlineAtMillis
        assertTrue(timer.beginFiring())

        timer.start(30)
        timer.endFiring()
        assertEquals(SleepTimerState.Running(now + 30 * minute, 30 * minute), timer.state.value)
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun extendAndCancelDoNotInterruptAFiringSequence() {
        val timer = timer()
        timer.start(15)
        now = running(timer).deadlineAtMillis
        assertTrue(timer.beginFiring())

        timer.extend()
        timer.cancel()
        assertEquals(SleepTimerState.Firing, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
    }

    // ---- onAlarm: the whole path with the platform side faked -------------------------------------------

    private class QuietActions : SleepEndActions {
        val calls = ArrayList<String>()
        var screenOn = true
        override fun isScreenOn(): Boolean = screenOn
        override fun warmUp() { calls.add("warmUp") }
        override fun playClosingSound() { calls.add("sound") }
        override fun sleep(millis: Long) { calls.add("sleep") }
        override fun isPlaying(): Boolean = false
        override fun perform(rung: PauseRung) { calls.add("pause:${rung.name}") }
        override fun release() { calls.add("release") }
        override fun startDimActivity() { calls.add("startDimActivity") }
        override fun dimActivityShown(): Boolean = true
        override fun abandonDimActivity() { calls.add("abandon") }
        override fun oxyfieldIsActiveScreensaver(): Boolean = false
        override fun requestDreamDim() { calls.add("requestDreamDim") }
        override fun startScreensaver(): Boolean = false
        override fun dreamDimConsumed(): Boolean = false
        override fun clearDreamDim() { calls.add("clearDreamDim") }
    }

    @Test
    fun onAlarmWhenNothingIsDueDoesAndPlaysNothing() {
        val actions = QuietActions()
        val built = AtomicInteger()
        val timer = SystemSleepTimer(context, now = { now }, endActions = { built.incrementAndGet(); actions }, settle = {})
        timer.start(30)
        now += 10 * minute // the alarm arrives early, e.g. a stale one

        val requested = AtomicInteger()
        assertNull(timer.onAlarm { requested.incrementAndGet() })
        assertEquals(0, built.get())
        assertTrue(actions.calls.isEmpty())
        assertEquals(0, requested.get())
        assertEquals(30 * minute, running(timer).totalMillis)
        assertNull(timer.lastReport())
    }

    @Test
    fun onAlarmWhenDueRunsTheSequenceAndLeavesNothingBehind() {
        val actions = QuietActions()
        val timer = SystemSleepTimer(context, now = { now }, endActions = { actions }, settle = {})
        timer.start(15)
        now = running(timer).deadlineAtMillis

        val requested = AtomicInteger()
        val outcome = timer.onAlarm { requested.incrementAndGet() }

        assertNotNull(outcome)
        assertTrue(outcome!!.dimShown)
        assertEquals(PauseResult.NOT_PLAYING, outcome.pause?.result)
        assertEquals(listOf("warmUp", "release", "sound", "startDimActivity"), actions.calls)
        assertEquals("the receiver is told once, right after the dim screen was requested", 1, requested.get())
        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        assertFalse(prefs.contains("deadline_at_millis"))

        val report = timer.lastReport()
        assertNotNull(report)
        assertTrue(report!!.contains("done, dim screen shown"))
        assertTrue(report.contains("dim strategy 'own-activity': SHOWN"))
    }

    @Test
    fun anExceptionBuildingThePlatformSideDoesNotLeaveTheTimerFiringAndIsRecorded() {
        val timer = SystemSleepTimer(
            context,
            now = { now },
            endActions = { throw IllegalStateException("boom") },
            settle = {},
        )
        timer.start(15)
        now = running(timer).deadlineAtMillis

        assertNull(timer.onAlarm())
        assertEquals(SleepTimerState.Idle, timer.state.value)
        val report = timer.lastReport()
        assertNotNull("a failed run is not silent", report)
        assertTrue(report!!, report.contains("sleep end: FAILED: IllegalStateException: boom"))
    }

    @Test
    fun aSecondAlarmWhileAnotherCallIsFiringLeavesTheFiringStateAlone() {
        val actions = QuietActions()
        val built = AtomicInteger()
        val timer = SystemSleepTimer(context, now = { now }, endActions = { built.incrementAndGet(); actions }, settle = {})
        timer.start(15)
        now = running(timer).deadlineAtMillis
        // Another delivery of the alarm is already mid-sequence: it holds the claim.
        assertTrue(timer.beginFiring())
        assertEquals(SleepTimerState.Firing, timer.state.value)

        val requested = AtomicInteger()
        assertNull(timer.onAlarm { requested.incrementAndGet() })

        assertEquals("the in-flight sequence is not cut short", SleepTimerState.Firing, timer.state.value)
        assertEquals(0, built.get())
        assertTrue(actions.calls.isEmpty())
        assertEquals(0, requested.get())
        assertNull("a call that claimed nothing writes no report", timer.lastReport())

        // The owner of the claim still hands it back.
        timer.endFiring()
        assertEquals(SleepTimerState.Idle, timer.state.value)
    }

    @Test
    fun aTvThatIsAlreadyOffGetsNoSoundNoPauseNoDimAndASkippedReport() {
        val actions = QuietActions().apply { screenOn = false }
        val timer = SystemSleepTimer(context, now = { now }, endActions = { actions }, settle = {})
        timer.start(15)
        now = running(timer).deadlineAtMillis

        val requested = AtomicInteger()
        val outcome = timer.onAlarm { requested.incrementAndGet() }

        assertEquals("TV already off", outcome!!.skipped)
        assertTrue("nothing was done: ${actions.calls}", actions.calls.isEmpty())
        assertEquals(0, requested.get())
        assertEquals(SleepTimerState.Idle, timer.state.value)
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        assertFalse(prefs.contains("deadline_at_millis"))
        assertTrue(timer.lastReport()!!.contains("TV already off, skipped"))
    }

    // ---- the early-alarm tolerance, through the claim ---------------------------------------------------

    @Test
    fun anAlarmFourSecondsBeforeTheDeadlineIsClaimed() {
        val timer = timer()
        timer.start(15)
        now = running(timer).deadlineAtMillis - 4_000L
        assertTrue(timer.beginFiring())
        assertEquals(SleepTimerState.Firing, timer.state.value)
    }

    @Test
    fun anAlarmSixSecondsBeforeTheDeadlineIsNotClaimed() {
        val timer = timer()
        timer.start(15)
        now = running(timer).deadlineAtMillis - 6_000L
        assertFalse(timer.beginFiring())
        assertEquals("still running, still scheduled", 1, shadowAlarms.scheduledAlarms.size)
        assertEquals(15 * minute, running(timer).totalMillis)
    }

    // ---- the grace for a cold process, in literal milliseconds ------------------------------------------

    @Test
    fun aNewInstance119SecondsAfterTheDeadlineStillHasTheTimerDue() {
        val deadline = timer().run { start(15); running(this).deadlineAtMillis }
        now = deadline + 119_000L
        val cold = timer()
        assertEquals(SleepTimerState.Running(deadline, 15 * minute), cold.state.value)
        assertTrue(cold.beginFiring())
    }

    @Test
    fun aNewInstance121SecondsAfterTheDeadlineDropsTheTimer() {
        val deadline = timer().run { start(15); running(this).deadlineAtMillis }
        now = deadline + 121_000L
        val cold = timer()
        assertEquals(SleepTimerState.Idle, cold.state.value)
        assertFalse(cold.beginFiring())
        assertFalse(prefs.contains("deadline_at_millis"))
    }
}

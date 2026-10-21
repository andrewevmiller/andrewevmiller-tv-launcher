package com.andrewevmiller.tvlauncher.sleep

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.andrewevmiller.tvlauncher.MainActivity
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.sound.NoOpFieldSound
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * The real sleep timer. The running timer lives in SharedPreferences ([SleepConfig.PREFS_NAME]) and in an
 * `AlarmManager.setAlarmClock` alarm, so it survives the launcher process being killed while another app is in front.
 * `setAlarmClock` needs no exact-alarm permission and is not deferred by Doze.
 *
 * The alarm lands in [SleepAlarmReceiver], which calls [onAlarm] on a worker thread. The receiver may be running in a
 * process that was started just for it, so a freshly created controller keeps a deadline that is only seconds past
 * (see [restoreTimer]) instead of dropping it as stale.
 *
 * All state changes are synchronized: the UI thread starts and cancels, the receiver's worker claims and hands back.
 *
 * @param now wall-clock milliseconds; tests move it.
 * @param prefs where the timer is kept; tests pass their own.
 * @param endActions builds the platform side of the end sequence, on first use (it is not built unless the timer
 *   fires). The default has no sound and no screensaver; AppContainer passes the real ones.
 */
class SystemSleepTimer(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(SleepConfig.PREFS_NAME, Context.MODE_PRIVATE),
    private val endActions: () -> SleepEndActions = {
        AndroidSleepEndActions(context.applicationContext, { NoOpFieldSound }, { NoOpScreensaverController })
    },
) : SleepTimerController, SleepEndTimer {

    private val appContext: Context = context.applicationContext
    private val alarms = SleepAlarmScheduler(appContext)
    private val mutable = MutableStateFlow<SleepTimerState>(SleepTimerState.Idle)
    override val state: StateFlow<SleepTimerState> = mutable

    init {
        val stored = restoreTimer(readLong(KEY_DEADLINE), readLong(KEY_TOTAL), now())
        synchronized(this) {
            mutable.value = stored.state
            if (stored.clearStore) {
                Log.i(SleepConfig.TAG, "dropping a stale stored timer")
                clearStore()
                alarms.cancel()
            }
            stored.alarmAtMillis?.let { at ->
                Log.i(SleepConfig.TAG, "restored ${stored.state}, alarm at $at")
                alarms.schedule(at)
            }
        }
    }

    @Synchronized
    override fun start(minutes: Int) {
        val running = SleepLogic.start(now(), minutes)
        Log.i(SleepConfig.TAG, "start ${running.totalMillis / MILLIS_PER_MINUTE} min, deadline ${running.deadlineAtMillis}")
        apply(running)
    }

    @Synchronized
    override fun extend(minutes: Int) {
        val current = mutable.value
        if (current !is SleepTimerState.Running) return
        val extended = SleepLogic.extend(current, now(), minutes) as SleepTimerState.Running
        Log.i(SleepConfig.TAG, "extend by $minutes min, deadline ${extended.deadlineAtMillis}")
        apply(extended)
    }

    @Synchronized
    override fun cancel() {
        Log.i(SleepConfig.TAG, "cancel (was ${mutable.value})")
        alarms.cancel()
        clearStore()
        // A sequence that is already firing is not interrupted; it hands itself back to Idle when it ends.
        if (mutable.value is SleepTimerState.Running) mutable.value = SleepTimerState.Idle
    }

    @Synchronized
    override fun beginFiring(): Boolean {
        if (!isFireable(mutable.value, now())) return false
        // Claim: from here nothing can fire this timer again, not a duplicate alarm and not a restart of the process.
        alarms.cancel()
        clearStore()
        mutable.value = SleepTimerState.Firing
        return true
    }

    @Synchronized
    override fun endFiring() {
        if (mutable.value is SleepTimerState.Firing) mutable.value = SleepTimerState.Idle
    }

    /**
     * Called by [SleepAlarmReceiver] on a worker thread when the alarm fires. Does nothing, and plays nothing, unless a
     * timer is due. Otherwise runs the whole end sequence and blocks for it: at most about 7.8 s of pause, closing
     * sound and settling, then the dim-screen request (the pause ladder is the long part, see [SleepEndRunner]).
     * [onDimRequested] is called as soon as a dim screen has been asked for; the receiver finishes its broadcast there.
     * The outcome is also saved as [lastReport]. Returns null when nothing was due.
     */
    fun onAlarm(onDimRequested: () -> Unit = {}): SleepEndOutcome? {
        Log.i(SleepConfig.TAG, "alarm received, state ${mutable.value}")
        val lines = ArrayList<String>()
        val started = System.currentTimeMillis()
        val log: (String) -> Unit = { line ->
            Log.i(SleepConfig.TAG, line)
            synchronized(lines) { lines.add(line) }
        }
        var outcome: SleepEndOutcome? = null
        var failed = false
        try {
            val runner = SleepEndRunner(
                timer = this,
                actionsFactory = endActions,
                settle = { delay(it) },
                log = log,
            )
            outcome = runBlocking { runner.run(onDimRequested) }
        } catch (e: Exception) {
            failed = true
            log("sleep end: FAILED: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Whatever happened above, a timer that was claimed must not stay in Firing.
            endFiring()
        }
        // A stray alarm (nothing due) leaves the previous report alone; a real run, or a crash in one, replaces it.
        if (outcome != null || failed) saveReport(started, synchronized(lines) { lines.toList() })
        return outcome
    }

    /** The log of the last time the timer fired, or null if it never has. For `adb shell run-as` and a future debug view. */
    fun lastReport(): String? = prefs.getString(KEY_LAST_REPORT, null)

    // commit(), not apply(): the receiver may be in a process that is killed right after, and the write is a few bytes.
    @SuppressLint("ApplySharedPref")
    private fun saveReport(startedAtMillis: Long, lines: List<String>) {
        val text = ("fired at $startedAtMillis\n" + lines.joinToString("\n")).takeLast(MAX_REPORT_CHARS)
        prefs.edit().putString(KEY_LAST_REPORT, text).commit()
    }

    /**
     * Stores [running], schedules its alarm (replacing any earlier one), and publishes it. The store is written with
     * commit(), not apply(): a process killed a moment later must not lose a timer whose alarm is already set.
     */
    @SuppressLint("ApplySharedPref")
    private fun apply(running: SleepTimerState.Running) {
        // Store first: if the process dies between the two lines the alarm still finds the deadline.
        prefs.edit()
            .putLong(KEY_DEADLINE, running.deadlineAtMillis)
            .putLong(KEY_TOTAL, running.totalMillis)
            .commit()
        alarms.schedule(running.deadlineAtMillis)
        mutable.value = running
    }

    @SuppressLint("ApplySharedPref")
    private fun clearStore() {
        prefs.edit().remove(KEY_DEADLINE).remove(KEY_TOTAL).commit()
    }

    private fun readLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    companion object {
        private const val KEY_DEADLINE = "deadline_at_millis"
        private const val KEY_TOTAL = "total_millis"
        private const val KEY_LAST_REPORT = "last_report"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_REPORT_CHARS = 6_000
    }
}

/**
 * The alarm behind the timer: one `setAlarmClock` alarm, always the same PendingIntent so a new schedule replaces the
 * old one and a cancel removes it. The PendingIntent is an explicit broadcast to [SleepAlarmReceiver]; the "show"
 * intent (what the system shows for an alarm clock) opens the launcher.
 */
class SleepAlarmScheduler(private val context: Context) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    // Lint wants SCHEDULE_EXACT_ALARM for any exact alarm. setAlarmClock is the documented exception: apps that set a
    // user-visible alarm clock need no permission, which is why this class does not declare it.
    @SuppressLint("MissingPermission")
    fun schedule(atMillis: Long) {
        val fire = fireIntent()
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent()), fire)
        } catch (e: SecurityException) {
            // Not expected (no permission is needed). An inexact alarm is better than a timer that never ends.
            Log.w(SleepConfig.TAG, "setAlarmClock refused (${e.message}); using setAndAllowWhileIdle")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, fire)
        }
    }

    fun cancel() {
        alarmManager.cancel(fireIntent())
    }

    private fun fireIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_FIRE,
        Intent(context, SleepAlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SHOW,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_FIRE = "com.andrewevmiller.tvlauncher.sleep.FIRE"
        private const val REQUEST_FIRE = 0x51EE
        private const val REQUEST_SHOW = 0x51EF
    }
}

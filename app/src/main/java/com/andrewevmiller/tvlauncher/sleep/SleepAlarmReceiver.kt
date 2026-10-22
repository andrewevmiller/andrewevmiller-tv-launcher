package com.andrewevmiller.tvlauncher.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andrewevmiller.tvlauncher.FieldApplication

/**
 * The sleep timer's alarm lands here, in whatever process state the launcher happens to be in (usually killed, with
 * YouTube or Netflix in front). Not exported: only our own PendingIntent reaches it.
 *
 * The end sequence takes several seconds (the pause ladder alone waits up to 6 s), so it runs on a worker thread and
 * the broadcast is held open with goAsync(). The system allows about 10 s, so the broadcast is finished
 *  - as soon as a dim screen has been asked for (the ladder and the closing sound are over by then), and
 *  - at the latest [SleepConfig.RELEASE_WATCHDOG_MILLIS] after it arrived, whatever the sequence is doing,
 *  - and in a finally block if the sequence ends or throws first.
 * All three go through one [OnceAction], so the PendingResult is finished exactly once. (Releasing at the dim request,
 * not at the end, is accepted for now: the process then has no component while the 3 s wait for the dim screen runs, but
 * the activity start that was just made keeps it alive in practice. The watchdog bounds the whole thing at 8.5 s.)
 *
 * What actually happens (and whether it is due at all) is decided by [SystemSleepTimer.onAlarm]: a cancelled or extended
 * timer finds nothing due, plays nothing, and returns at once. An intent that is not our alarm returns before goAsync.
 *
 * The body is [handle], with the system pieces behind [AlarmHooks], so a test can run the worker inline and fire the
 * watchdog by hand.
 */
class SleepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        handle(
            context,
            intent,
            object : AlarmHooks {
                override fun timer(context: Context): SystemSleepTimer? {
                    val application = context.applicationContext as? FieldApplication
                    return application?.container?.sleepTimer as? SystemSleepTimer
                }

                override fun holdBroadcast(): () -> Unit {
                    val pending = goAsync() // null outside a real broadcast delivery
                    return { pending?.finish() }
                }

                override fun postDelayed(delayMillis: Long, action: () -> Unit) {
                    Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMillis)
                }

                override fun startWorker(work: Runnable) {
                    Thread(work, "FieldSleepEnd").start()
                }
            },
        )
    }

    internal fun handle(context: Context, intent: Intent, hooks: AlarmHooks) {
        if (intent.action != SleepAlarmScheduler.ACTION_FIRE) return
        val timer = hooks.timer(context)
        if (timer == null) {
            Log.w(SleepConfig.TAG, "alarm received but there is no SystemSleepTimer to hand it to")
            return
        }

        // Held before anything below can fail, so every path from here on ends in release().
        val finish = hooks.holdBroadcast()
        val release = OnceAction {
            Log.i(SleepConfig.TAG, "broadcast released")
            finish()
        }
        try {
            hooks.postDelayed(SleepConfig.RELEASE_WATCHDOG_MILLIS) { release() }
            hooks.startWorker(
                Runnable {
                    try {
                        timer.onAlarm { release() }
                    } catch (t: Throwable) {
                        Log.e(SleepConfig.TAG, "end sequence crashed", t)
                    } finally {
                        release()
                    }
                },
            )
        } catch (t: Throwable) {
            Log.e(SleepConfig.TAG, "could not start the end sequence", t)
            release()
        }
    }
}

/** The system pieces [SleepAlarmReceiver.handle] uses, so a test can stand in for them. */
internal interface AlarmHooks {
    /** The timer the alarm belongs to, or null if this application has none. */
    fun timer(context: Context): SystemSleepTimer?

    /** goAsync(): holds the broadcast open. Returns what finishes it. Not called for an intent that is not ours. */
    fun holdBroadcast(): () -> Unit

    /** Runs [action] on the main thread after [delayMillis]. */
    fun postDelayed(delayMillis: Long, action: () -> Unit)

    /** Starts [work] on a worker thread. May throw. */
    fun startWorker(work: Runnable)
}

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
 * All three go through one [OnceAction], so the PendingResult is finished exactly once.
 *
 * What actually happens (and whether it is due at all) is decided by [SystemSleepTimer.onAlarm]: a cancelled or extended
 * timer finds nothing due, plays nothing, and returns at once.
 */
class SleepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepAlarmScheduler.ACTION_FIRE) return
        val application = context.applicationContext as? FieldApplication
        val timer = application?.container?.sleepTimer as? SystemSleepTimer
        if (timer == null) {
            Log.w(SleepConfig.TAG, "alarm received but there is no SystemSleepTimer to hand it to")
            return
        }

        val pending = goAsync()
        val release = OnceAction {
            Log.i(SleepConfig.TAG, "broadcast released")
            pending?.finish()
        }
        Handler(Looper.getMainLooper()).postDelayed({ release() }, SleepConfig.RELEASE_WATCHDOG_MILLIS)

        val worker = Thread({
            try {
                timer.onAlarm { release() }
            } catch (t: Throwable) {
                Log.e(SleepConfig.TAG, "end sequence crashed", t)
            } finally {
                release()
            }
        }, "FieldSleepEnd")
        try {
            worker.start()
        } catch (t: Throwable) {
            Log.e(SleepConfig.TAG, "could not start the end sequence", t)
            release()
        }
    }
}

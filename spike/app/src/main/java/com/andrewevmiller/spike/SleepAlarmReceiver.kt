package com.andrewevmiller.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * E3: the alarm lands here, not in an activity, exactly as the launcher's real sleep timer will. Everything
 * after this point runs from the background of whichever app the owner is watching.
 *
 * Thin on purpose: [SleepProbe.fire] does the work on a worker thread so it can wait between steps.
 * goAsync() keeps the broadcast open for the pause ladder; it is released before the 3 s detection wait.
 */
class SleepAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val method = intent.getStringExtra(Spike.EXTRA_METHOD) ?: "?"
        val id = intent.getIntExtra(Spike.EXTRA_RUN, -1)
        Log.i(Spike.TAG, "SleepAlarmReceiver.onReceive run=$id method=$method")
        if (method == E3Method.TEST || id < 0) return

        val pending: PendingResult? = goAsync()
        val finished = AtomicBoolean(false)
        val release: () -> Unit = {
            if (finished.compareAndSet(false, true)) pending?.finish()
        }
        val app = context.applicationContext ?: context
        Thread {
            try {
                SleepProbe(app) { }.fire(id, method) { release() }
            } finally {
                release()
            }
        }.start()
    }
}

package com.andrewevmiller.tvlauncher.ambient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andrewevmiller.tvlauncher.settings.Screensaver

private const val TAG = SystemScreensaverController.TAG

/**
 * Photos tile action: show the owner's Google Photos slideshow by borrowing Google TV's ambient mode.
 *
 * Switches the system screensaver to Google Photos, starts it, and puts [current] (the screensaver the
 * owner chose in our settings) back when the dream ends. If switching isn't allowed or the device
 * refuses the on-demand start, [onMessage] gets a short hint instead; the integrator shows it in the
 * design language. What happens when is decided by [PhotosHandoffPolicy].
 *
 * Call from the main thread. Pass an application or activity context; only the application context is kept.
 */
fun startPhotosSlideshow(
    context: Context,
    controller: ScreensaverController,
    current: Screensaver,
    onMessage: (String) -> Unit,
) {
    val plan = PhotosHandoffPolicy.plan(controller.canSwitch, current)
    Log.i(TAG, "photos hand-off: $plan")

    if (plan.action == HandoffAction.APPLY_AND_START) controller.apply(Screensaver.GOOGLE_PHOTOS)
    val started = if (plan.action == HandoffAction.HINT_ONLY) false else controller.startNow()
    val outcome = PhotosHandoffPolicy.outcome(plan, started)
    Log.i(TAG, "photos hand-off: started=$started outcome=$outcome")

    val restoreTo = plan.restoreTo
    when {
        restoreTo != null && outcome.restoreNow -> {
            PhotosRestore.cancelPending()
            controller.apply(restoreTo)
        }
        restoreTo != null && outcome.restoreAfterDream ->
            PhotosRestore.arm(context.applicationContext ?: context, controller, restoreTo)
        // We switched but there is nothing to put back (Photos already was the owner's choice):
        // drop any older pending restore so it can't undo that later.
        plan.action == HandoffAction.APPLY_AND_START -> PhotosRestore.cancelPending()
    }

    outcome.message?.let(onMessage)
}

/** At most one pending "put the owner's screensaver back" at a time. */
private object PhotosRestore {
    private val lock = Any()
    private var pending: PendingRestore? = null

    fun arm(appContext: Context, controller: ScreensaverController, restoreTo: Screensaver) {
        synchronized(lock) {
            pending?.cancel()
            val next = PendingRestore(appContext, controller, restoreTo)
            pending = next
            next.start()
        }
    }

    fun cancelPending() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
        }
    }

    fun finished(done: PendingRestore) {
        synchronized(lock) {
            if (pending === done) pending = null
        }
    }
}

/**
 * One-shot receiver for ACTION_DREAMING_STOPPED that restores [restoreTo] and unregisters itself. If the
 * broadcast never arrives (or registering failed), the same restore happens after
 * [PhotosHandoffPolicy.SAFETY_TIMEOUT_MS]: the dream reads the setting only when it starts, so putting it
 * back mid-dream is harmless.
 */
private class PendingRestore(
    private val appContext: Context,
    private val controller: ScreensaverController,
    private val restoreTo: Screensaver,
) : BroadcastReceiver() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish(restore = true, why = "safety timeout") }
    private var done = false
    private var registered = false

    fun start() {
        try {
            // Protected system broadcast, so no export flag is needed.
            appContext.registerReceiver(this, IntentFilter(Intent.ACTION_DREAMING_STOPPED))
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "could not register for DREAMING_STOPPED, relying on the timeout: ${e.message}")
        }
        handler.postDelayed(timeout, PhotosHandoffPolicy.SAFETY_TIMEOUT_MS)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        finish(restore = true, why = "dream stopped")
    }

    /** Stops waiting without restoring anything. */
    fun cancel() {
        finish(restore = false, why = "cancelled")
    }

    private fun finish(restore: Boolean, why: String) {
        synchronized(this) {
            if (done) return
            done = true
        }
        handler.removeCallbacks(timeout)
        if (registered) {
            registered = false
            try {
                appContext.unregisterReceiver(this)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        if (restore) {
            Log.i(TAG, "photos hand-off: restoring $restoreTo ($why)")
            controller.apply(restoreTo)
        }
        PhotosRestore.finished(this)
    }
}

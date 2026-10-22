package com.andrewevmiller.tvlauncher.safety

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-loop safety net. If the launcher crashes twice within 60 seconds of process start, Home goes
 * back to the previous launcher (see [HomeFallback]) and the next manual start shows a notice.
 *
 * Wiring (integrator): `CrashGuard.install(this)` in `Application.onCreate`, then in `MainActivity`
 * `CrashGuard.maybeForceCrash(this, intent)` in `onCreate`, and show
 * `FallbackNoticeBanner(notice, onDismiss = { CrashGuard.acknowledge(context) })` while
 * `CrashGuard.pendingNotice(context)` is non-null.
 *
 * Every entry point swallows its own failures: this code must never be the reason the launcher crashes.
 */
object CrashGuard {
    private const val TAG = "CrashGuard"
    private const val KEY_CRASH_STATE = "crash_state"

    /** Debug-only test hook: `adb shell am start -n .../.MainActivity --ez field.forceCrash true`. */
    const val EXTRA_FORCE_CRASH = "field.forceCrash"

    private val handling = AtomicBoolean(false)
    private var installed = false

    /**
     * Records this process's start time and installs the default uncaught-exception handler. The
     * previous handler (the platform's, which shows the crash dialog and kills the process) always
     * still runs, after our bookkeeping.
     */
    @Synchronized
    fun install(app: Application) {
        if (installed) return
        installed = true

        val detector = CrashLoopDetector(PrefsCrashStore(prefs(app)))
        try {
            detector.recordProcessStart(System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "could not record the process start", t)
        }

        val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    // One crash per process: a second thread dying at the same moment is not a second crash.
                    if (handling.compareAndSet(false, true)) {
                        onUncaught(app, detector)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "crash handler failed", t)
                } finally {
                    chain(previous, thread, throwable)
                }
            },
        )
    }

    /** The notice to show while a fallback record exists, or null. */
    fun pendingNotice(context: Context): FallbackNotice? {
        return try {
            val flattened = prefs(context).getString(HomeFallback.KEY_FALLBACK_TARGET, null)
            if (flattened == null) null else FallbackNotice.forPreviousHome(labelFor(context, flattened))
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the fallback record", t)
            null
        }
    }

    /** Clears the fallback record so the notice is not shown again. */
    fun acknowledge(context: Context) {
        try {
            prefs(context).edit()
                .remove(HomeFallback.KEY_FALLBACK_TARGET)
                .remove(HomeFallback.KEY_FALLBACK_AT)
                .commit()
        } catch (t: Throwable) {
            Log.w(TAG, "could not clear the fallback record", t)
        }
    }

    /**
     * Re-enables our HOME alias. Enabling it does not make us the default home on its own;
     * `scripts/deploy.sh` does that with `cmd package set-home-activity`.
     * Returns true when PackageManager accepted the change.
     */
    fun reenableHome(context: Context): Boolean {
        return try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, HomeFallback.HOME_ALIAS_CLASS),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not re-enable the HOME alias", t)
            false
        }
    }

    /**
     * Test hook for the owner's forced-crash check. Does nothing unless the app is debuggable and the
     * intent carries boolean extra [EXTRA_FORCE_CRASH]; then it throws, which the uncaught-exception
     * handler above sees like any other crash.
     */
    fun maybeForceCrash(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra(EXTRA_FORCE_CRASH, false)) {
            throw RuntimeException("forced crash for fallback test")
        }
    }

    private fun onUncaught(app: Application, detector: CrashLoopDetector) {
        if (!detector.onCrash(System.currentTimeMillis())) return
        val result = HomeFallback.handOffHome(app)
        Log.w(TAG, "crash loop detected; fallback result: $result")
        if (result is FallbackResult.HandedOff) {
            // Home is no longer ours; the next manual start begins with a clean slate.
            detector.reset()
        }
    }

    private fun chain(
        previous: Thread.UncaughtExceptionHandler?,
        thread: Thread,
        throwable: Throwable,
    ) {
        if (previous != null) {
            previous.uncaughtException(thread, throwable)
        } else {
            // No default handler existed: do what the JVM would have done.
            thread.threadGroup?.uncaughtException(thread, throwable)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(HomeFallback.PREFS_NAME, Context.MODE_PRIVATE)

    /** Human-readable name of the launcher that got Home; falls back to the package name. */
    private fun labelFor(context: Context, flattened: String): String {
        val component = HomeComponent.unflatten(flattened) ?: return flattened
        return try {
            val pm = context.packageManager
            val info = pm.getActivityInfo(ComponentName(component.packageName, component.className), 0)
            val activityLabel = info.loadLabel(pm).toString()
            if (activityLabel.isNotBlank()) activityLabel else pm.getApplicationLabel(info.applicationInfo).toString()
        } catch (t: Throwable) {
            component.packageName
        }
    }

    /** [CrashStore] over SharedPreferences. `commit()` because the process may die right after. */
    private class PrefsCrashStore(private val prefs: SharedPreferences) : CrashStore {
        override fun load(): CrashState =
            CrashStateCodec.decode(prefs.getString(KEY_CRASH_STATE, null))

        override fun save(state: CrashState) {
            prefs.edit().putString(KEY_CRASH_STATE, CrashStateCodec.encode(state)).commit()
        }
    }
}

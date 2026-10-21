package com.andrewevmiller.tvlauncher.sleep

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import com.andrewevmiller.tvlauncher.FieldApplication
import com.andrewevmiller.tvlauncher.R
import com.andrewevmiller.tvlauncher.ambient.OxyfieldDreamService
import com.andrewevmiller.tvlauncher.ambient.OxyfieldPalette
import com.andrewevmiller.tvlauncher.ambient.OxyfieldView
import com.andrewevmiller.tvlauncher.ambient.SystemReducedMotionProvider
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.theme.DarkFieldColors

/**
 * The sleep screen when the dream route is not used: the Oxyfield dot field, fading to near-black over 45 s and then
 * holding one still frame, in a plain Activity of its own (no Compose, exactly as the dream hosts [OxyfieldView]).
 *
 * Started by [AndroidSleepEndActions] from the alarm receiver, with another app in front. It records that it was created
 * in [SleepDimHandshake], which is how the worker learns the start was not silently blocked. It lives in its own task
 * (own affinity, singleTask, not in Recents), so it never drags Home to the front, and Home works as normal: pressing it
 * closes this screen too, since a dark screen must not linger behind the launcher.
 *
 * Any key press wakes it (after [SleepConfig.DIM_KEY_GRACE_MILLIS], so a key still being released when it appears does
 * not): the wake sound plays and the screen closes. Media stays paused; nothing here ever resumes it. It also closes
 * itself after [SleepConfig.DIM_AUTO_FINISH_MILLIS].
 */
class SleepDimActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var view: OxyfieldView? = null
    private var shownAt = 0L
    private var waking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!markShown("onCreate")) return

        val provider = OxyfieldDreamService.reducedMotionProvider ?: SystemReducedMotionProvider(this)
        val oxyfield = OxyfieldView(
            context = this,
            palette = darkPalette(),
            reducedMotion = provider.isReduced(),
            typeface = plexMono(),
        )
        view = oxyfield
        // Armed before the first frame, so a reduced-motion screen never paints a bright frame first.
        oxyfield.startSleepDim()
        setContentView(oxyfield)
        oxyfield.start()
        handler.postDelayed({ close("auto-finish after ${SleepConfig.DIM_AUTO_FINISH_MILLIS / 60_000L} min") }, SleepConfig.DIM_AUTO_FINISH_MILLIS)
    }

    /** singleTask: a second start while we are alive lands here, not in a new instance. Restart the key grace only. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        markShown("onNewIntent")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Only a fresh press wakes it, not the auto-repeat of a key that was already down when the screen came up.
        if (event != null && event.repeatCount > 0) return true
        val age = SystemClock.elapsedRealtime() - shownAt
        if (age < SleepConfig.DIM_KEY_GRACE_MILLIS) {
            Log.i(SleepConfig.TAG, "SleepDimActivity ignored ${KeyEvent.keyCodeToString(keyCode)} $age ms after it appeared")
            return true
        }
        wake("key ${KeyEvent.keyCodeToString(keyCode)}")
        // The volume keys still work (the system handles them if we do not); everything else is ours.
        return !isVolumeKey(keyCode)
    }

    override fun onStop() {
        super.onStop()
        // Home was pressed (or the TV went off): the dark screen has done its job, do not leave it behind the launcher.
        if (!isFinishing && !isChangingConfigurations) close("stopped")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        view?.stop()
        // Cleared only now: while the window is still animating out, a repaint at full brightness would flash.
        view?.clearSleepDim()
        view = null
        super.onDestroy()
    }

    private fun markShown(from: String): Boolean {
        shownAt = SystemClock.elapsedRealtime()
        val allowed = SleepDimHandshake.shown(shownAt)
        Log.i(SleepConfig.TAG, "SleepDimActivity.$from at $shownAt, allowed=$allowed")
        if (!allowed) {
            // The worker gave up on this start and moved on to another strategy: do not show on top of it.
            finishAndRemoveTask()
        }
        return allowed
    }

    private fun wake(why: String) {
        if (waking) return
        waking = true
        Log.i(SleepConfig.TAG, "SleepDimActivity waking: $why")
        view?.stop()
        playWake()
        finishAndRemoveTask()
    }

    private fun close(why: String) {
        if (waking) return
        waking = true
        Log.i(SleepConfig.TAG, "SleepDimActivity closing: $why")
        view?.stop()
        finishAndRemoveTask()
    }

    private fun playWake() {
        try {
            val sound = soundOverride?.invoke()
                ?: (application as? FieldApplication)?.container?.sound
            sound?.wake()
        } catch (e: Exception) {
            Log.w(SleepConfig.TAG, "wake sound failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    private fun plexMono(): Typeface =
        try {
            resources.getFont(R.font.plex_mono_regular)
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }

    private fun darkPalette(): OxyfieldPalette = OxyfieldPalette(
        bg = DarkFieldColors.bg.toArgb(),
        dot = DarkFieldColors.line.toArgb(),
        muted = DarkFieldColors.muted.toArgb(),
        accent = DarkFieldColors.accent.toArgb(),
        ink = DarkFieldColors.ink.toArgb(),
    )

    companion object {
        /** Test seam: when set, the wake sound goes here instead of the app container's engine. */
        @Volatile
        internal var soundOverride: (() -> FieldSound)? = null
    }
}

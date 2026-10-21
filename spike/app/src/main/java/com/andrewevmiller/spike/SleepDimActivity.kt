package com.andrewevmiller.spike

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * E3: the near-black "sleep surface". Method B and C start this from the alarm receiver while another app is
 * in front. onCreate writes the time into the run record, which is how the receiver learns the start was not
 * silently blocked (Android 10+ drops background activity starts without an exception).
 *
 * Finishes on any key press (after a short grace period, so a key still held down when it appears does not
 * hide the evidence) and on its own after [AUTO_FINISH_MS], so a test never leaves the TV on a black screen.
 */
class SleepDimActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SleepStore
    private lateinit var timeView: TextView
    private var runId = -1
    private var createdAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            timeView.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = SystemClock.elapsedRealtime()
        store = SleepStore(applicationContext)
        runId = intent.getIntExtra(Spike.EXTRA_RUN, -1)
        record(E3Field.DIM_CREATED_AT, createdAt.toString())
        Log.i(Spike.TAG, "SleepDimActivity.onCreate run=$runId at elapsedRealtime=$createdAt")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Spike.DIM_BG)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "FIELD SPIKE · SLEEP SURFACE"
        title.typeface = Typeface.MONOSPACE
        title.textSize = 22f
        title.setTextColor(Spike.DIM_TEXT)
        title.gravity = Gravity.CENTER
        column.addView(title)

        timeView = TextView(this)
        timeView.typeface = Typeface.MONOSPACE
        timeView.textSize = 18f
        timeView.setTextColor(Spike.DIM_TEXT)
        timeView.gravity = Gravity.CENTER
        timeView.setPadding(0, 24, 0, 0)
        column.addView(timeView)

        val hint = TextView(this)
        hint.text = "any key closes this (or it closes itself in 90 s)"
        hint.typeface = Typeface.MONOSPACE
        hint.textSize = 12f
        hint.setTextColor(Spike.DIM_TEXT)
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, 24, 0, 0)
        column.addView(hint)

        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        tick.run()
        handler.postDelayed({ dismiss("auto-finish after " + (AUTO_FINISH_MS / 1000) + " s") }, AUTO_FINISH_MS)
    }

    /** singleTask: a second start while we are alive lands here instead of in a new instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runId = intent.getIntExtra(Spike.EXTRA_RUN, runId)
        createdAt = SystemClock.elapsedRealtime()
        record(E3Field.DIM_CREATED_AT, createdAt.toString())
        Log.i(Spike.TAG, "SleepDimActivity.onNewIntent run=$runId at elapsedRealtime=$createdAt")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(Spike.TAG, "SleepDimActivity window focus=$hasFocus run=$runId")
        if (hasFocus) record(E3Field.DIM_FOCUS_AT, SystemClock.elapsedRealtime().toString())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val age = SystemClock.elapsedRealtime() - createdAt
        if (age < GRACE_MS) {
            Log.i(Spike.TAG, "SleepDimActivity ignored " + KeyEvent.keyCodeToString(keyCode) + " " + age + " ms after it appeared")
            return true
        }
        dismiss("key " + KeyEvent.keyCodeToString(keyCode) + " after " + (age / 1000) + " s")
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Log.i(Spike.TAG, "SleepDimActivity.onDestroy run=$runId")
        super.onDestroy()
    }

    private fun dismiss(why: String) {
        Log.i(Spike.TAG, "SleepDimActivity finishing run=$runId: $why")
        record(E3Field.DIM_DISMISSED, why)
        handler.removeCallbacksAndMessages(null)
        finishAndRemoveTask()
    }

    private fun record(field: String, value: String) {
        if (runId >= 0) store.put(runId, field, value, sync = false)
    }

    private companion object {
        const val GRACE_MS = 700L
        const val AUTO_FINISH_MS = 90_000L
    }
}

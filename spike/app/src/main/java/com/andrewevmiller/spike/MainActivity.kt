package com.andrewevmiller.spike

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One screen, two spikes. Left column: D-pad focusable buttons. Right column: results (focus it with
 * Right, scroll with Up/Down). Every result line is also written to logcat tag "FieldSpike".
 *
 * Plain framework Views on purpose: nothing to go wrong at compile time except the two probes.
 */
class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var dreamBox: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var epg: EpgProbe
    private lateinit var dream: DreamProbe

    private val buf = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private class Attempt(val label: String, val screensaver: String, val call: String) {
        var lostFocus = "pending"
        var visible = "?"
    }

    private val attempts = ArrayList<Attempt>()

    /** Set when our window loses focus or the activity pauses after an attempt starts. */
    @Volatile
    private var leftForeground = false

    // ---- lifecycle ------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Spike.PREFS, MODE_PRIVATE)
        epg = EpgProbe(applicationContext)
        dream = DreamProbe(this) { line -> emit(line) }
        buildUi()
        rebuildDreamButtons()
        refreshStatus()
        emitIntro()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        leftForeground = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) leftForeground = true
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_EPG) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            emit("runtime dialog result: READ_EPG_DATA granted=" + granted +
                (if (grantResults.isEmpty()) " (dialog dismissed or not shown)" else ""))
            refreshStatus()
        }
    }

    // ---- output ---------------------------------------------------------------------------------

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /** Thread-safe: writes to logcat and appends to the results pane. */
    private fun emit(line: String) {
        Log.i(Spike.TAG, line)
        runOnUiThread { appendLine(line) }
    }

    private fun appendLine(line: String) {
        buf.append(line).append('\n')
        if (buf.length > MAX_CHARS) buf.delete(0, buf.length - KEEP_CHARS)
        out.text = buf.toString()
    }

    private fun emitIntro() {
        emit("FIELD SPIKE  (E1 Watch Next, E2 screensaver)")
        emit(Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE +
            " (API " + Build.VERSION.SDK_INT + ")")
        emit("Up/Down: buttons. Right: focus this pane, then Up/Down scrolls. Left: back to buttons.")
        emit("E1: press E1.0, then E1.1. Change the permission state with adb (spike/README.md), run E1.1 again, then E1.3.")
        emit("E2: press E2.0 and E2.1, then a, b1, b2, b3, c. After each, if a screensaver appeared press any key to wake, then mark it.")
        emit("Every line is also in: adb logcat -s FieldSpike")
        emit("")
    }

    private fun refreshStatus() {
        statusView.text = ReportFormat.stateTitle(epg.state()) + "\nWRITE_SECURE_SETTINGS: " +
            (if (dream.hasWriteSecure()) "granted" else "not granted")
    }

    // ---- E1 -------------------------------------------------------------------------------------

    private fun e1Permissions() {
        emit("--- " + now() + " E1.0 permission status")
        for (l in epg.permissionLines()) emit("  " + l)
        emit("  grant:  adb shell pm grant " + packageName + " " + Spike.PERM_READ_EPG)
        emit("  revoke: adb shell pm revoke " + packageName + " " + Spike.PERM_READ_EPG)
        refreshStatus()
    }

    private fun e1Run() {
        refreshStatus()
        io.execute {
            val state = epg.state()
            emit("")
            emit("=== " + now() + " E1.1 run, " + ReportFormat.stateTitle(state))
            for (l in epg.permissionLines().take(3)) emit("  " + l)
            val results: List<TableResult> = try {
                epg.runAll()
            } catch (e: Throwable) {
                emit("E1 run failed: " + e.javaClass.simpleName + ": " + e.message)
                return@execute
            }
            for (r in results) for (l in ReportFormat.detailLines(r)) emit(l)
            val block = results.joinToString("\n") { ReportFormat.oneLine(it) }
            prefs.edit().putString(PREF_E1_PREFIX + state, block).apply()
            emit("saved as " + ReportFormat.stateTitle(state) + ". Press E1.3 for the combined summary.")
        }
    }

    private fun e1Request() {
        emit("--- " + now() + " E1.2 asking for READ_EPG_DATA through the runtime permission dialog")
        requestPermissions(arrayOf(Spike.PERM_READ_EPG), REQ_EPG)
    }

    private fun e1Summary() {
        val blocks = HashMap<Int, String?>()
        for (s in 1..3) blocks[s] = prefs.getString(PREF_E1_PREFIX + s, null)
        val header = Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE +
            " (API " + Build.VERSION.SDK_INT + ")"
        emit("")
        for (l in ReportFormat.e1Summary(header, blocks)) emit(l)
    }

    // ---- E2 -------------------------------------------------------------------------------------

    private fun runAttempt(label: String, strategy: () -> String) {
        emit("")
        emit("--- " + now() + " E2 attempt: " + label)
        val ss = dream.currentScreensaver()
        emit("    screensaver_components = " + ss)
        leftForeground = false
        val result = try {
            strategy()
        } catch (e: Throwable) {
            "uncaught " + e.javaClass.simpleName + ": " + e.message
        }
        emit("    result: " + result)
        val attempt = Attempt(label, ss, result)
        attempts.add(attempt)
        handler.postDelayed({
            attempt.lostFocus = if (leftForeground) "yes" else "no"
            emit("    +2.5s: our window lost focus = " + attempt.lostFocus + " (yes = something took the screen)")
            emit("    If a screensaver is showing, press any key to wake, come back, and mark it below.")
        }, 2500)
    }

    private fun mark(visible: String) {
        val last = attempts.lastOrNull()
        if (last == null) {
            emit("no attempt to mark yet")
            return
        }
        last.visible = visible
        emit("marked '" + last.label + "' as visible=" + visible)
    }

    private fun e2Summary() {
        emit("")
        emit("E2 SUMMARY  " + Build.MANUFACTURER + " " + Build.MODEL + ", API " + Build.VERSION.SDK_INT)
        if (attempts.isEmpty()) emit("  (no attempts yet)")
        for (a in attempts) {
            for (l in ReportFormat.e2Row(a.label, a.screensaver, a.call, a.lostFocus, a.visible)) emit(l)
        }
    }

    private fun rebuildDreamButtons() {
        dreamBox.removeAllViews()
        val all = dream.dreamPackageActivities()
        val usable = all.filter { it.exported && it.enabled }
        if (all.isEmpty()) {
            addNote(dreamBox, "(" + Spike.DREAM_PACKAGE + " has no activities, or is not installed)")
            return
        }
        for (a in usable) {
            val short = ReportFormat.shortClass(a.name, Spike.DREAM_PACKAGE)
            addButton(dreamBox, "E2.c  " + short) {
                runAttempt("c " + short) { dream.fire(dream.intentDreamActivity(a.name)) }
            }
        }
        val skipped = all.size - usable.size
        if (usable.isEmpty()) {
            addNote(dreamBox, "(no exported+enabled activities; " + skipped + " other, see E2.0)")
        } else if (skipped > 0) {
            addNote(dreamBox, "(" + skipped + " not exported or disabled, see E2.0)")
        }
    }

    // ---- UI -------------------------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.HORIZONTAL
        root.setBackgroundColor(BG)
        // 5% safe area on a 960x540dp canvas.
        root.setPadding(dp(48), dp(27), dp(48), dp(27))

        val leftScroll = ScrollView(this)
        leftScroll.isFocusable = false
        leftScroll.isVerticalScrollBarEnabled = false
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        leftScroll.addView(left, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusView = TextView(this)
        statusView.typeface = Typeface.MONOSPACE
        statusView.textSize = 13f
        statusView.setTextColor(ACCENT)
        left.addView(statusView, fullWidth(topMargin = 0))

        addHeader(left, "E1  WATCH NEXT / PREVIEW ROWS")
        addButton(left, "E1.0  Permission status") { e1Permissions() }
        addButton(left, "E1.1  Run the 3 queries") { e1Run() }
        addButton(left, "E1.2  Ask for READ_EPG_DATA") { e1Request() }
        addButton(left, "E1.3  Summary, all states") { e1Summary() }

        addHeader(left, "E2  START SCREENSAVER NOW")
        addButton(left, "E2.0  Screensaver + system state") {
            emit("")
            dream.info()
            refreshStatus()
        }
        addButton(left, "E2.1  Sweep: what resolves") {
            emit("")
            dream.sweep()
        }
        addButton(left, "E2.a  Somnambulator (MAIN)") {
            runAttempt("a Somnambulator") { dream.fire(dream.intentSomnambulator()) }
        }
        addButton(left, "E2.b1  Somnambulator + DESK_DOCK") {
            runAttempt("b1 Somnambulator+DESK_DOCK") { dream.fire(dream.intentSomnambulatorDock()) }
        }
        addButton(left, "E2.b2  Implicit MAIN + DESK_DOCK") {
            runAttempt("b2 implicit DESK_DOCK") { dream.fire(dream.intentImplicitDock()) }
        }
        addButton(left, "E2.b3  Dream settings page") {
            runAttempt("b3 DREAM_SETTINGS") { dream.fire(dream.intentDreamSettings()) }
        }

        addHeader(left, "E2.c  DREAM PACKAGE ACTIVITIES")
        addButton(left, "E2.c0  Rescan dream package") {
            emit("")
            rebuildDreamButtons()
            emit("rebuilt the E2.c buttons from PackageManager")
        }
        dreamBox = LinearLayout(this)
        dreamBox.orientation = LinearLayout.VERTICAL
        left.addView(dreamBox, fullWidth(topMargin = 0))

        addHeader(left, "E2.d  POINT SCREENSAVER AT BACKDROP")
        addButton(left, "E2.d1  SET to dreamx Backdrop") {
            emit("")
            emit("--- " + now() + " E2.d1 set screensaver_components")
            emit("    result: " + dream.setToBackdrop())
            refreshStatus()
        }
        addButton(left, "E2.d2  RESTORE previous value") {
            emit("")
            emit("--- " + now() + " E2.d2 restore screensaver_components")
            emit("    result: " + dream.restore())
            refreshStatus()
        }
        addNote(left, "After d1, press a / b1 / b2 / c again, then d2.")

        addHeader(left, "RESULTS")
        addButton(left, "Mark last attempt: SHOWED") { mark("SHOWED") }
        addButton(left, "Mark last attempt: NOTHING") { mark("NOTHING") }
        addButton(left, "E2 summary") { e2Summary() }
        // Fallback in case Right does not move focus to the results pane on this TV.
        addButton(left, "Results page up") { scroll.smoothScrollBy(0, -scroll.height * 3 / 4) }
        addButton(left, "Results page down") { scroll.smoothScrollBy(0, scroll.height * 3 / 4) }
        addButton(left, "Clear results pane") {
            buf.setLength(0)
            out.text = ""
        }

        root.addView(leftScroll, LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT))

        scroll = ScrollView(this)
        scroll.isFocusable = true
        scroll.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        scroll.isScrollbarFadingEnabled = false
        scroll.background = outline(FILL, LINE)
        scroll.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) outline(FILL, ACCENT) else outline(FILL, LINE)
        }
        out = TextView(this)
        out.typeface = Typeface.MONOSPACE
        out.textSize = 14f
        out.setTextColor(TEXT)
        out.setPadding(dp(12), dp(10), dp(12), dp(10))
        // OnLayoutChangeListener args: view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom.
        out.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            // Follow the newest line, but only when the content actually grew.
            if (bottom - top != oldBottom - oldTop) scroll.post { scroll.scrollTo(0, out.height) }
        }
        scroll.addView(out, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rightParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        rightParams.leftMargin = dp(16)
        root.addView(scroll, rightParams)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun fullWidth(topMargin: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topMargin)
        return lp
    }

    private fun addHeader(parent: LinearLayout, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 13f
        tv.setTextColor(MUTED)
        parent.addView(tv, fullWidth(topMargin = 18))
    }

    private fun addNote(parent: LinearLayout, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(MUTED)
        parent.addView(tv, fullWidth(topMargin = 4))
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(TEXT)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.minHeight = 0
        b.minimumHeight = dp(40)
        b.setPadding(dp(14), dp(6), dp(14), dp(6))
        b.background = buttonBackground()
        b.setOnClickListener { action() }
        parent.addView(b, fullWidth(topMargin = 4))
        return b
    }

    private fun outline(fill: Int, stroke: Int, strokeDp: Int = 1): Drawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.setStroke(dp(strokeDp), stroke)
        d.cornerRadius = dp(4).toFloat()
        return d
    }

    /** Focus = accent outline (the only emphasis); idle = hairline. A new instance per view. */
    private fun buttonBackground(): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), outline(FILL_FOCUS, ACCENT, 3))
        states.addState(intArrayOf(android.R.attr.state_pressed), outline(FILL_FOCUS, ACCENT, 3))
        states.addState(intArrayOf(), outline(FILL, LINE))
        return states
    }

    private companion object {
        const val REQ_EPG = 1
        const val MAX_CHARS = 40000
        const val KEEP_CHARS = 30000
        const val PREF_E1_PREFIX = "e1_state_"

        // Same palette as the launcher's dark theme. A throwaway spike, so plain constants here.
        const val BG = 0xFF171716.toInt()
        const val FILL = 0xFF1F1F1D.toInt()
        const val FILL_FOCUS = 0xFF2A2A27.toInt()
        const val LINE = 0xFF3A3A35.toInt()
        const val TEXT = 0xFFECEBE5.toInt()
        const val MUTED = 0xFFA3A29A.toInt()
        const val ACCENT = 0xFFB98FD6.toInt()
    }
}

package com.andrewevmiller.tvlauncher.ambient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Choreographer
import android.view.View
import java.util.Calendar
import kotlin.random.Random

/**
 * The Oxyfield screensaver surface: a dot field on a 44 px grid (at 1920 wide) whose dots brighten
 * line -> muted -> accent as ripples pass, plus the clock bottom-left: the time with seconds
 * ("19:42:07", "7:42:07 PM") and the weekday/date line.
 *
 * Plain Canvas, no Compose: a DreamService has no lifecycle or saved-state owner for a ComposeView.
 * All maths lives in the pure Oxyfield* files; this class owns paints, the frame loop and the clock.
 *
 * Animation runs off Choreographer and only invalidates at [OxyfieldSpec.MAX_FPS]; it stops when the view
 * is detached or its window is hidden. The clock text is rebuilt once per second, not per frame. With
 * [reducedMotion] it paints one still frame and never loops; the clock alone repaints, once a second from a
 * main-looper Handler (a ticking number is information, not animation, so it is allowed under reduced motion).
 *
 * Sleep mode ([startSleepDim]) is a pure addition: the whole picture fades to near-black over
 * [SleepDimMath.FADE_MILLIS], the clock fades to a faint floor ([SleepDimMath.CLOCK_FLOOR]) sooner and stays, and
 * once settled the view draws one final frame and stops animating for good. The sleep clock shows the minute only,
 * no seconds: a ticking second on a dark screen is distracting and would keep waking the GPU. A settled frame is
 * therefore not quite still: it repaints when the minute changes (ACTION_TIME_TICK), with the dots drawn exactly as
 * in the last frame, so the clock text is the only thing that changes. The view does not listen for keys; waking
 * is the host's job (the dream wakes on any key). Until [startSleepDim] is called every colour and code path is
 * exactly the normal one.
 *
 * Burn-in: a still clock on a settled screen is a risk on OLED panels. The Hisense is believed to be an LCD, so
 * there is no pixel shifting for now; judge it on the TV.
 */
class OxyfieldView(
    context: Context,
    private val palette: OxyfieldPalette,
    private val reducedMotion: Boolean,
    private val typeface: Typeface,
    private val random: Random = Random(System.nanoTime()),
) : View(context) {

    private var grid: OxyfieldGrid? = null
    private var rippleField: OxyfieldRippleField? = null
    private var still: OxyfieldFrame? = null
    private var clockLayout: OxyfieldClockLayout? = null
    private var idlePoints = FloatArray(0)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = palette.dot
    }

    // The time uses digits that are all the same width (Plex Mono, or the system monospace if the font is missing),
    // so the seconds can tick without the rest of the string shifting sideways.
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@OxyfieldView.typeface
        color = palette.ink
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@OxyfieldView.typeface
        color = palette.muted
        letterSpacing = 0.08f
    }

    // The clock strings are cached by the second (by the minute on the sleep screen): onDraw runs at 30 fps but
    // only rebuilds them when the key moves, so a frame allocates nothing for the clock.
    private var timeText = ""
    private var dateText = ""
    private var clockKey = Long.MIN_VALUE
    private var clockHasSeconds = true
    private var clockDirty = true
    private var calendar: Calendar = Calendar.getInstance()

    /** Test seam: the wall clock in epoch milliseconds. Setting it makes the next draw rebuild the clock text. */
    internal var wallClock: () -> Long = { System.currentTimeMillis() }
        set(value) {
            field = value
            clockDirty = true
        }

    /** Test seam: the 12/24 hour choice (the TV setting by default). Setting it rebuilds the clock text. */
    internal var is24Hour: () -> Boolean = { DateFormat.is24HourFormat(context) }
        set(value) {
            field = value
            clockDirty = true
        }

    /** The time string as last drawn ("19:42:07", or "19:42" on the sleep screen). For tests. */
    internal val clockTimeText: String get() = timeText

    // Animation state (unused in reduced motion).
    private val gate = FrameGate(OxyfieldSpec.MAX_FPS)
    private var running = false
    private var frameMs = 0L

    // Sleep fade. Inactive (both levels exactly 1) until startSleepDim().
    private val sleep = SleepDimState(reducedMotion)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val nowMs = frameTimeNanos / 1_000_000L
            if (gate.shouldRender(nowMs)) {
                advanceTo(nowMs)
                if (sleep.settled) {
                    // The fade is over and its last frame is queued: stop for good. A still frame costs no CPU.
                    running = false
                    return
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // Reduced motion, normal screensaver: there is no frame loop, so the seconds come from a Handler aimed at each
    // whole second. It is not used on the sleep screen (minute text), and stop() and detach remove it.
    private var secondTickerEnabled = false
    private val secondTick = object : Runnable {
        override fun run() {
            if (!secondTickerEnabled) return
            invalidate()
            scheduleSecondTick()
        }
    }

    // The system's minute tick (and a time or zone change). Registered whenever the view is attached and its
    // window is visible, in every mode: it repaints the minute of a settled sleep frame and of a reduced-motion
    // one, and tells the cached text to rebuild (a new time zone) while the frame loop runs.
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!clockReceiverRegistered) return // a broadcast already in flight when we unregistered
            clockDirty = true
            // The frame loop repaints by itself. Otherwise this is the only thing that redraws the clock: one
            // invalidate and no frame step, so the dots are drawn from the last frame's stored time and only the
            // clock text can differ from the frame before.
            if (!running) invalidate()
            // The tick is aimed at the minute boundary. If it ever lands a hair early the draw would still show the
            // old minute and a still screen would wait a whole minute to fix it: look again just after the boundary.
            if (intent?.action == Intent.ACTION_TIME_TICK && OxyfieldClockText.tickLandedEarly(wallClock())) {
                handler.removeCallbacks(minuteRecheck)
                handler.postDelayed(minuteRecheck, OxyfieldClockText.millisToNextMinute(wallClock()))
            }
        }
    }
    private var clockReceiverRegistered = false
    private val minuteRecheck = Runnable {
        clockDirty = true
        if (!running) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val g = OxyfieldGrid(w, h)
        grid = g
        idlePoints = FloatArray(g.columns * g.rows * 2)
        idlePaint.strokeWidth = g.dotRadius * 2f

        val layout = oxyfieldClockLayout(w, h)
        clockLayout = layout
        timePaint.textSize = layout.timeSize
        datePaint.textSize = layout.dateSize

        if (reducedMotion) {
            still = oxyfieldStillFrame(w, h)
        } else {
            val f = OxyfieldRippleField(random, w, h)
            f.reset(if (running) frameMs else System.nanoTime() / 1_000_000L)
            rippleField = f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) resume() else pause()
    }

    /** Attached with a visible window: listen for the minute tick and start whatever drives the picture. */
    private fun resume() {
        registerClockReceiver()
        clockDirty = true // the time may have moved on while the window was hidden
        start()
    }

    /** Detached or hidden: nothing may repaint, so drop the loop, the second ticker, the receiver and any recheck. */
    private fun pause() {
        stop()
        handler.removeCallbacks(minuteRecheck)
        unregisterClockReceiver()
    }

    /**
     * Starts the 30 fps loop. Safe to call repeatedly. A settled sleep frame stays still: it only repaints.
     * With reduced motion there is no loop; it starts the once-a-second clock ticker instead.
     */
    fun start() {
        if (running || !isAttachedToWindow) return
        if (reducedMotion) {
            secondTickerEnabled = true
            scheduleSecondTick()
            invalidate()
            return
        }
        if (sleep.settled) {
            invalidate()
            return
        }
        running = true
        gate.reset()
        frameMs = System.nanoTime() / 1_000_000L
        rippleField?.reset(frameMs)
        Choreographer.getInstance().postFrameCallback(frameCallback)
        invalidate()
    }

    /** Stops the loop and the second ticker; nothing is scheduled afterwards. */
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        secondTickerEnabled = false
        handler.removeCallbacks(secondTick)
    }

    /**
     * (Re)aims the second ticker at just after the next whole second, from the wall clock each time so it never
     * drifts. Only the normal reduced-motion screensaver has one: the sleep screen shows minutes.
     */
    private fun scheduleSecondTick() {
        handler.removeCallbacks(secondTick)
        if (!secondTickerEnabled || sleep.active) return
        handler.postDelayed(secondTick, OxyfieldClockText.millisToNextSecond(wallClock()))
    }

    /**
     * Begins the sleep fade at [nowMillis], a time on the Choreographer clock (the default is now). Call it
     * when the sleep screen goes up; calling it again restarts the fade. With reduced motion the dim still frame
     * is shown at once. It never starts the loop by itself: the host still calls [start], as for the normal
     * screensaver (and must call it again to animate after a settled fade).
     */
    fun startSleepDim(nowMillis: Long = System.nanoTime() / 1_000_000L) {
        sleep.start(nowMillis)
        scheduleSecondTick() // the sleep clock has no seconds: this removes the reduced-motion ticker
        invalidate()
    }

    /** Back to the normal bright screensaver. Call [start] afterwards if the loop had stopped. */
    fun clearSleepDim() {
        sleep.clear()
        scheduleSecondTick() // seconds are back: re-aim the reduced-motion ticker if it is enabled
        invalidate()
    }

    /** True while the sleep fade is running or held. */
    val isSleepDimming: Boolean get() = sleep.active

    /** True once the fade is over and the view holds its final still frame. */
    val isSleepSettled: Boolean get() = sleep.settled

    /**
     * One animation step at [nowMs]: the loop calls this, and tests drive it without a Choreographer.
     * After the sleep fade settles it does nothing, so the final frame never changes.
     */
    internal fun advanceTo(nowMs: Long) {
        if (sleep.settled) return
        frameMs = nowMs
        rippleField?.advance(nowMs)
        sleep.update(nowMs)
        invalidate()
    }

    private fun registerClockReceiver() {
        if (clockReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // All three are protected system broadcasts, so no export flag is needed.
        context.registerReceiver(clockReceiver, filter)
        clockReceiverRegistered = true
    }

    private fun unregisterClockReceiver() {
        if (!clockReceiverRegistered) return
        clockReceiverRegistered = false
        try {
            context.unregisterReceiver(clockReceiver)
        } catch (e: IllegalArgumentException) {
            // Already gone; nothing to do.
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 1 unless the sleep fade is on, in which case the whole picture is the normal frame at this brightness.
        val level = sleep.level(frameMs)
        val dimmed = level < 1f
        canvas.drawColor(if (dimmed) SleepDimMath.scaleColor(palette.bg, level) else palette.bg)
        val g = grid ?: return

        val ripples: List<Ripple>
        val nowMs: Long
        if (reducedMotion) {
            val frame = still
            ripples = frame?.ripples ?: emptyList()
            nowMs = frame?.timeMs ?: 0L
        } else {
            ripples = rippleField?.ripples ?: emptyList()
            nowMs = frameMs
        }

        idlePaint.color = if (dimmed) SleepDimMath.scaleColor(palette.dot, level) else palette.dot
        var idle = 0
        for (row in 0 until g.rows) {
            val y = g.y(row)
            for (col in 0 until g.columns) {
                val x = g.x(col)
                val e = rippleEnergy(x, y, ripples, nowMs)
                if (e < OxyfieldSpec.IDLE_ENERGY) {
                    idlePoints[idle] = x
                    idlePoints[idle + 1] = y
                    idle += 2
                } else {
                    val color = oxyfieldDotColor(e, palette)
                    dotPaint.color = if (dimmed) SleepDimMath.scaleColor(color, level) else color
                    canvas.drawCircle(x, y, oxyfieldDotRadius(e, g.dotRadius), dotPaint)
                }
            }
        }
        if (idle > 0) canvas.drawPoints(idlePoints, 0, idle, idlePaint)

        // On the sleep screen the clock shows the minute only, from the first moment of the dim.
        drawClock(canvas, sleep.clockLevel(frameMs), showSeconds = !sleep.active)
    }

    private fun drawClock(canvas: Canvas, clockLevel: Float, showSeconds: Boolean) {
        val layout = clockLayout ?: return
        if (clockLevel <= 0f) return // invisible anyway (the sleep floor is above zero, so this is only a guard)
        timePaint.color = if (clockLevel < 1f) SleepDimMath.scaleColor(palette.ink, clockLevel) else palette.ink
        datePaint.color = if (clockLevel < 1f) SleepDimMath.scaleColor(palette.muted, clockLevel) else palette.muted
        refreshClockText(showSeconds)
        canvas.drawText(timeText, layout.left, layout.timeBaseline, timePaint)
        canvas.drawText(dateText, layout.left, layout.dateBaseline, datePaint)
    }

    /**
     * Rebuilds the clock strings when the second (the minute, without [showSeconds]) changes, when the mode
     * switches between the two, or when the system says the time or zone moved. Otherwise it only compares a
     * long, so a 30 fps frame allocates nothing here.
     */
    private fun refreshClockText(showSeconds: Boolean) {
        val wall = wallClock()
        val key = if (showSeconds) OxyfieldClockText.secondKey(wall) else OxyfieldClockText.minuteKey(wall)
        if (!clockDirty && showSeconds == clockHasSeconds && key == clockKey) return
        if (clockDirty) calendar = Calendar.getInstance() // the default time zone may have changed
        clockDirty = false
        clockHasSeconds = showSeconds
        clockKey = key
        val cal = calendar
        cal.timeInMillis = wall
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        timeText = if (showSeconds) {
            OxyfieldClockText.time(hour, minute, cal.get(Calendar.SECOND), is24Hour())
        } else {
            OxyfieldClockText.time(hour, minute, is24Hour())
        }
        dateText = OxyfieldClockText.date(
            cal.get(Calendar.DAY_OF_WEEK),
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }
}

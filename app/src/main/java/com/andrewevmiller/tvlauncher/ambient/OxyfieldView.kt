package com.andrewevmiller.tvlauncher.ambient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Choreographer
import android.view.View
import java.util.Calendar
import kotlin.random.Random

/**
 * The Oxyfield screensaver surface: a dot field on a 44 px grid (at 1920 wide) whose dots brighten
 * line -> muted -> accent as ripples pass, plus the clock bottom-left.
 *
 * Plain Canvas, no Compose: a DreamService has no lifecycle or saved-state owner for a ComposeView.
 * All maths lives in the pure Oxyfield* files; this class owns paints, the frame loop and the clock.
 *
 * Animation runs off Choreographer and only invalidates at [OxyfieldSpec.MAX_FPS]; it stops when the view
 * is detached or its window is hidden. With [reducedMotion] it paints one still frame and never loops
 * (the clock alone repaints, driven by the system's minute tick).
 *
 * Sleep mode ([startSleepDim]) is a pure addition: the whole picture fades to near-black over
 * [SleepDimMath.FADE_MILLIS], the clock leaves sooner, and once settled the view draws one final frame and
 * stops animating for good. It does not listen for keys; waking is the host's job (the dream wakes on any key).
 * Until [startSleepDim] is called every colour and code path is exactly the normal one.
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
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@OxyfieldView.typeface
        color = palette.ink
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@OxyfieldView.typeface
        color = palette.muted
        letterSpacing = 0.08f
    }

    private var timeText = ""
    private var dateText = ""
    private var clockMinuteKey = Long.MIN_VALUE
    private var clockDirty = true

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

    // Reduced motion: repaint the clock once a minute, event-driven rather than a loop.
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clockDirty = true
            // A settled sleep frame has no clock on it: repainting would only wake the GPU for nothing.
            if (!sleep.settled) invalidate()
        }
    }
    private var clockReceiverRegistered = false

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
        if (reducedMotion) registerClockReceiver() else start()
    }

    override fun onDetachedFromWindow() {
        stop()
        unregisterClockReceiver()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (reducedMotion) return
        if (visibility == View.VISIBLE) start() else stop()
    }

    /** Starts the 30 fps loop. Safe to call repeatedly. A settled sleep frame stays still: it only repaints. */
    fun start() {
        if (reducedMotion || running || !isAttachedToWindow) return
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

    /** Stops the loop; nothing is scheduled afterwards. */
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * Begins the sleep fade at [nowMillis], a time on the Choreographer clock (the default is now). Call it
     * when the sleep screen goes up; calling it again restarts the fade. With reduced motion the dim still frame
     * is shown at once. It never starts the loop by itself: the host still calls [start], as for the normal
     * screensaver (and must call it again to animate after a settled fade).
     */
    fun startSleepDim(nowMillis: Long = System.nanoTime() / 1_000_000L) {
        sleep.start(nowMillis)
        invalidate()
    }

    /** Back to the normal bright screensaver. Call [start] afterwards if the loop had stopped. */
    fun clearSleepDim() {
        sleep.clear()
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

        drawClock(canvas, sleep.clockLevel(frameMs))
    }

    private fun drawClock(canvas: Canvas, clockLevel: Float) {
        val layout = clockLayout ?: return
        if (clockLevel <= 0f) return // faded out (or settled): nothing to draw, nothing to refresh
        timePaint.color = if (clockLevel < 1f) SleepDimMath.scaleColor(palette.ink, clockLevel) else palette.ink
        datePaint.color = if (clockLevel < 1f) SleepDimMath.scaleColor(palette.muted, clockLevel) else palette.muted
        refreshClockText()
        canvas.drawText(timeText, layout.left, layout.timeBaseline, timePaint)
        canvas.drawText(dateText, layout.left, layout.dateBaseline, datePaint)
    }

    /** Recomputes the clock strings when the minute changes (or the system says the time or zone moved). */
    private fun refreshClockText() {
        val wall = System.currentTimeMillis()
        val key = wall / 60_000L
        if (!clockDirty && key == clockMinuteKey) return
        clockDirty = false
        clockMinuteKey = key
        val cal = Calendar.getInstance()
        cal.timeInMillis = wall
        timeText = OxyfieldClockText.time(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(context),
        )
        dateText = OxyfieldClockText.date(
            cal.get(Calendar.DAY_OF_WEEK),
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }
}

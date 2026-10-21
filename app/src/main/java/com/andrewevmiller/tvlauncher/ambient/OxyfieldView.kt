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

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val nowMs = frameTimeNanos / 1_000_000L
            if (gate.shouldRender(nowMs)) {
                frameMs = nowMs
                rippleField?.advance(nowMs)
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // Reduced motion: repaint the clock once a minute, event-driven rather than a loop.
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clockDirty = true
            invalidate()
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

    /** Starts the 30 fps loop. Safe to call repeatedly. */
    fun start() {
        if (reducedMotion || running || !isAttachedToWindow) return
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
        canvas.drawColor(palette.bg)
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
                    dotPaint.color = oxyfieldDotColor(e, palette)
                    canvas.drawCircle(x, y, oxyfieldDotRadius(e, g.dotRadius), dotPaint)
                }
            }
        }
        if (idle > 0) canvas.drawPoints(idlePoints, 0, idle, idlePaint)

        drawClock(canvas)
    }

    private fun drawClock(canvas: Canvas) {
        val layout = clockLayout ?: return
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

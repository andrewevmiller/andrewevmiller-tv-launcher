package com.andrewevmiller.tvlauncher.ambient

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/*
 * Pure math for the Oxyfield screensaver: grid layout, ripple energy, colour mixing, the 30 fps frame gate
 * and the clock layout. No Android types, so it is unit-tested with purecheck. The mockup's `field()` in
 * docs/mockup/field-launcher-mockup.html is the reference; all numbers below are in its 1920x1080 space.
 */

object OxyfieldSpec {
    const val REF_WIDTH = 1920f
    const val REF_HEIGHT = 1080f

    /** Dot pitch at 1920 px wide; scaled with width / 1920. */
    const val GRID_SPACING = 44f
    const val DOT_RADIUS = 2.2f

    /** A dot at full energy is drawn this much bigger: radius * (1 + energy * RADIUS_GAIN). */
    const val RADIUS_GAIN = 1.3f

    const val SPAWN_INTERVAL_MS = 2400L
    const val RIPPLE_WIDTH = 55f
    const val RIPPLE_LIFE_SEC = 7f
    const val SPEED_MIN = 220f
    const val SPEED_SPAN = 80f

    /** Hard cap on live ripples (7 s life / 2.4 s spawn is about 3, plus the two seeds). */
    const val MAX_RIPPLES = 8

    const val MAX_FPS = 30

    /** Dots below this energy are drawn in one batched call as the resting colour. */
    const val IDLE_ENERGY = 0.01f

    /** A ripple contributes nothing farther than this many widths from its ring (exp(-8) = 0.0003). */
    const val CUTOFF_SIGMAS = 4f

    /** 5% title-safe margin. */
    const val SAFE_FRACTION = 0.05f
}

/**
 * Dots sit at spacing/2 + i*spacing while < extent, exactly like the mockup's loops.
 * Spacing scales with width / 1920, so 4K gets the same 44 x 25 field as 1080p.
 */
class OxyfieldGrid(val width: Int, val height: Int) {
    val scale: Float = if (width > 0) width / OxyfieldSpec.REF_WIDTH else 0f
    val spacing: Float = maxOf(OxyfieldSpec.GRID_SPACING * scale, 1f)
    val columns: Int = if (width > 0) oxyfieldDotCount(width, spacing) else 0
    val rows: Int = if (height > 0) oxyfieldDotCount(height, spacing) else 0
    val dotRadius: Float = OxyfieldSpec.DOT_RADIUS * scale
    val dotCount: Int get() = columns * rows

    fun x(column: Int): Float = spacing / 2f + column * spacing
    fun y(row: Int): Float = spacing / 2f + row * spacing
}

internal fun oxyfieldDotCount(extent: Int, spacing: Float): Int {
    val n = ceil((extent - spacing / 2f) / spacing).toInt()
    return if (n < 0) 0 else n
}

/**
 * One expanding ring. Times are milliseconds on whatever monotonic clock the caller uses; distances are
 * in view pixels (already scaled).
 */
data class Ripple(
    val x: Float,
    val y: Float,
    val startMs: Long,
    /** Ring speed, px per second. */
    val speed: Float,
    /** Gaussian sigma of the ring, px. */
    val width: Float,
    val lifeSec: Float,
) {
    fun ageSec(nowMs: Long): Float = (nowMs - startMs) / 1000f

    fun isExpired(nowMs: Long): Boolean = ageSec(nowMs) >= lifeSec

    /** Distance of the ring from the centre. */
    fun radius(nowMs: Long): Float = ageSec(nowMs) * speed

    /** Linear fade from 1 at birth to 0 at the end of life. */
    fun fade(nowMs: Long): Float {
        if (lifeSec <= 0f) return 0f
        val f = 1f - ageSec(nowMs) / lifeSec
        return if (f < 0f) 0f else if (f > 1f) 1f else f
    }

    /**
     * This ripple's energy at (px, py): a Gaussian around the ring, scaled by the fade. 0 before birth,
     * after death, and beyond [OxyfieldSpec.CUTOFF_SIGMAS] widths from the ring.
     */
    fun energyAt(px: Float, py: Float, nowMs: Long): Float {
        val age = ageSec(nowMs)
        if (age < 0f || age >= lifeSec || width <= 0f || lifeSec <= 0f) return 0f
        val dx = px - x
        val dy = py - y
        val delta = sqrt(dx * dx + dy * dy) - age * speed
        val cutoff = OxyfieldSpec.CUTOFF_SIGMAS * width
        if (delta > cutoff || delta < -cutoff) return 0f
        return exp(-(delta * delta) / (2f * width * width)) * (1f - age / lifeSec)
    }
}

/** Summed ripple energy at a point, clamped to 0..1. Allocation free (indexed loop). */
fun rippleEnergy(px: Float, py: Float, ripples: List<Ripple>, nowMs: Long): Float {
    var e = 0f
    for (i in 0 until ripples.size) e += ripples[i].energyAt(px, py, nowMs)
    return if (e > 1f) 1f else e
}

/** Opaque dot palette as ARGB ints, built from `DarkFieldColors` by the Android layer. */
data class OxyfieldPalette(
    val bg: Int,
    /** Resting dot colour (the `line` token). */
    val dot: Int,
    val muted: Int,
    val accent: Int,
    /** Clock time text. */
    val ink: Int,
)

private fun mixChannel(a: Int, b: Int, t: Float, shift: Int): Int {
    val ca = (a ushr shift) and 0xFF
    val cb = (b ushr shift) and 0xFF
    val v = (ca + (cb - ca) * t + 0.5f).toInt()
    return if (v < 0) 0 else if (v > 255) 255 else v
}

/** Per-channel linear mix of two ARGB colours; t is clamped to 0..1. */
fun lerpColor(a: Int, b: Int, t: Float): Int {
    val tt = if (t < 0f) 0f else if (t > 1f) 1f else t
    return (mixChannel(a, b, tt, 24) shl 24) or
        (mixChannel(a, b, tt, 16) shl 16) or
        (mixChannel(a, b, tt, 8) shl 8) or
        mixChannel(a, b, tt, 0)
}

/**
 * line -> muted over energy 0..0.5, then muted -> accent over 0.5..1, so the accent appears only where a
 * ripple's ring is strong.
 */
fun oxyfieldDotColor(energy: Float, palette: OxyfieldPalette): Int =
    if (energy < 0.5f) lerpColor(palette.dot, palette.muted, energy * 2f)
    else lerpColor(palette.muted, palette.accent, (energy - 0.5f) * 2f)

fun oxyfieldDotRadius(energy: Float, baseRadius: Float): Float =
    baseRadius * (1f + energy * OxyfieldSpec.RADIUS_GAIN)

/**
 * Caps rendering to [maxFps]. Feed it every vsync time; it says which ones should produce a frame.
 * A tolerance lets a 60 Hz stream (16.7 ms steps, rounded to ms) land on every second vsync = 30 fps
 * instead of slipping to every third.
 */
class FrameGate(maxFps: Int = OxyfieldSpec.MAX_FPS, private val toleranceMs: Long = 4L) {
    private val minIntervalMs: Long = 1000L / maxFps.coerceAtLeast(1)
    private var lastMs: Long = 0L
    private var hasLast: Boolean = false

    fun shouldRender(nowMs: Long): Boolean {
        if (!hasLast || nowMs < lastMs || nowMs - lastMs >= minIntervalMs - toleranceMs) {
            hasLast = true
            lastMs = nowMs
            return true
        }
        return false
    }

    fun reset() {
        hasLast = false
    }
}

/** Where the clock goes, in view pixels. Everything stays inside the 5% safe area. */
data class OxyfieldClockLayout(
    val left: Float,
    val timeBaseline: Float,
    val dateBaseline: Float,
    val timeSize: Float,
    val dateSize: Float,
)

fun oxyfieldClockLayout(width: Int, height: Int): OxyfieldClockLayout {
    val s = if (width > 0) width / OxyfieldSpec.REF_WIDTH else 0f
    val timeSize = 80f * s
    val dateSize = 26f * s
    val left = width * OxyfieldSpec.SAFE_FRACTION
    val bottom = height * (1f - OxyfieldSpec.SAFE_FRACTION)
    // Leave room under the date baseline for descent so no glyph crosses the safe edge.
    val dateBaseline = bottom - dateSize * 0.25f
    val timeBaseline = dateBaseline - dateSize - 16f * s
    return OxyfieldClockLayout(left, timeBaseline, dateBaseline, timeSize, dateSize)
}

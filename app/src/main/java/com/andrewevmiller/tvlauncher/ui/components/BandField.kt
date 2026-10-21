package com.andrewevmiller.tvlauncher.ui.components

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One expanding ring in the band's dot field. [xFrac]/[yFrac] are the origin as fractions of the canvas;
 * [age] is seconds since it was spawned.
 */
data class BandRipple(val xFrac: Float, val yFrac: Float, val age: Float)

/**
 * The math behind the feature band's small dot field (the mockup's `field()` at 600x216 px, i.e. a
 * 300x108 dp canvas): dots on a grid, a slow ripple brightens them line -> muted -> accent.
 *
 * Everything is a pure function of time, so a frame is reproducible (previews, the reduced-motion still
 * frame, tests) and there is no mutable ripple list to manage. Lengths are in dp.
 */
object BandField {
    const val SPACING_DP = 9f
    const val DOT_RADIUS_DP = 0.8f

    /** Ripple front speed, ring half-width and lifetime (mockup: 110 px/s, 24 px, 5 s at 2x). */
    const val SPEED_DP_PER_SEC = 55f
    const val WIDTH_DP = 12f
    const val LIFE_SEC = 5f

    /** A new ripple every 2.5 s, so two rings overlap at any time. */
    const val SPAWN_INTERVAL_SEC = 2.5f

    /** The ripple pattern repeats every [CYCLE_SEC] so time never grows without bound (float precision). */
    const val CYCLE_COUNT = 240
    const val CYCLE_SEC = SPAWN_INTERVAL_SEC * CYCLE_COUNT

    /** The single frame shown when reduced motion is on (the mockup freezes at 1.4 s). */
    const val STILL_TIME_SEC = 1.4f

    /** Frame cap: at least 30 ms between redraws (a 60 Hz display then draws every other frame, ~30 fps). */
    const val MIN_FRAME_INTERVAL_NANOS = 30_000_000L

    private const val LIVE_CANDIDATES = 3 // ceil(LIFE / SPAWN_INTERVAL) + 1, the age test drops dead ones

    /**
     * Pattern time for [elapsedNanos] since the band appeared: starts at [STILL_TIME_SEC] (so the first frame
     * already has a ring on screen, like the mockup) and wraps every [CYCLE_SEC] to keep floats precise
     * however long Home stays open.
     */
    fun timeForElapsed(elapsedNanos: Long): Float {
        val cycleNanos = (CYCLE_SEC.toDouble() * 1e9).toLong()
        val within = elapsedNanos.coerceAtLeast(0L) % cycleNanos
        return STILL_TIME_SEC + (within / 1e9).toFloat()
    }

    /** Dot radius grows with energy: up to 2.3x at the ring's crest. */
    fun dotRadiusDp(energy: Float): Float = DOT_RADIUS_DP * (1f + energy * 1.3f)

    /** The ripples alive at [timeSec] (any non-negative time; the pattern wraps every [CYCLE_SEC]). */
    fun activeRipples(timeSec: Float): List<BandRipple> {
        val t = wrap(timeSec, CYCLE_SEC)
        val newest = floor(t / SPAWN_INTERVAL_SEC).toInt()
        val out = ArrayList<BandRipple>(LIVE_CANDIDATES)
        for (j in 0 until LIVE_CANDIDATES) {
            val k = (newest - j).mod(CYCLE_COUNT)
            val age = wrap(t - k * SPAWN_INTERVAL_SEC, CYCLE_SEC)
            if (age < LIFE_SEC) {
                out.add(BandRipple(originX(k), originY(k), age))
            }
        }
        return out
    }

    /**
     * Dot energy in [0, 1] at ([xDp], [yDp]) on a [widthDp] x [heightDp] canvas: each ripple contributes a
     * gaussian ring (front at `age * speed`) that fades linearly over its life; contributions add and clamp.
     */
    fun energyAt(xDp: Float, yDp: Float, widthDp: Float, heightDp: Float, ripples: List<BandRipple>): Float {
        var e = 0f
        for (r in ripples) {
            val dx = xDp - r.xFrac * widthDp
            val dy = yDp - r.yFrac * heightDp
            val d = sqrt(dx * dx + dy * dy)
            val front = r.age * SPEED_DP_PER_SEC
            val fade = (1f - r.age / LIFE_SEC).coerceAtLeast(0f)
            val off = d - front
            e += exp(-(off * off) / (2f * WIDTH_DP * WIDTH_DP)) * fade
        }
        return min(1f, e)
    }

    private fun originX(k: Int): Float = if (k == 0) 0.63f else 0.1f + 0.8f * unit(mix32(2 * k + 1))
    private fun originY(k: Int): Float = if (k == 0) 0.46f else 0.14f + 0.62f * unit(mix32(2 * k + 2))

    private fun wrap(v: Float, period: Float): Float {
        val r = v % period
        return if (r < 0f) r + period else r
    }

    /**
     * 32-bit integer finaliser (murmur3 style): a stable pseudo-random value per ripple number.
     * The multipliers are the usual golden-ratio / murmur3 constants written as signed decimals.
     */
    internal fun mix32(x: Int): Int {
        var h = x * -1640531527
        h = h xor (h ushr 16)
        h *= -2048144789
        h = h xor (h ushr 13)
        h *= -1028477387
        h = h xor (h ushr 16)
        return h
    }

    /** Top 24 bits as a float in [0, 1). */
    internal fun unit(h: Int): Float = (h ushr 8) / 16777216f
}

/**
 * Limits redraws to roughly 30 fps. Feed it each frame's timestamp; it answers whether to redraw.
 * The first frame is always accepted.
 */
class BandFrameCap(private val minIntervalNanos: Long = BandField.MIN_FRAME_INTERVAL_NANOS) {
    private var last = 0L
    private var started = false

    fun accept(nowNanos: Long): Boolean {
        if (!started || nowNanos - last >= minIntervalNanos) {
            started = true
            last = nowNanos
            return true
        }
        return false
    }
}

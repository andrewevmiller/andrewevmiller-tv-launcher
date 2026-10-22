package com.andrewevmiller.tvlauncher.ambient

import kotlin.random.Random

/*
 * Pure scene state for Oxyfield: which ripples exist at a given time. Ports `setAmbient`/`ambLoop` from the
 * mockup. Randomness comes from an injected [Random] so tests are deterministic.
 */

/** Ripples and the clock time to paint them at. */
class OxyfieldFrame(val ripples: List<Ripple>, val timeMs: Long)

/**
 * The two ripples the mockup starts with, already part-way across the field so the first frame has
 * something in it. Positions are fractions of the view so any aspect ratio works.
 */
fun oxyfieldSeedRipples(width: Int, height: Int, nowMs: Long): List<Ripple> {
    val scale = if (width > 0) width / OxyfieldSpec.REF_WIDTH else 0f
    return listOf(
        Ripple(
            x = width * (1200f / OxyfieldSpec.REF_WIDTH),
            y = height * (420f / OxyfieldSpec.REF_HEIGHT),
            startMs = nowMs - 2200L,
            speed = 260f * scale,
            width = OxyfieldSpec.RIPPLE_WIDTH * scale,
            lifeSec = OxyfieldSpec.RIPPLE_LIFE_SEC,
        ),
        Ripple(
            x = width * (520f / OxyfieldSpec.REF_WIDTH),
            y = height * (700f / OxyfieldSpec.REF_HEIGHT),
            startMs = nowMs - 500L,
            speed = 260f * scale,
            width = OxyfieldSpec.RIPPLE_WIDTH * scale,
            lifeSec = OxyfieldSpec.RIPPLE_LIFE_SEC,
        ),
    )
}

/**
 * The reduced-motion frame: the mockup's two seed ripples frozen 2.6 s after the first one was born.
 * Independent of any clock, so it is the same every time.
 */
fun oxyfieldStillFrame(width: Int, height: Int): OxyfieldFrame {
    val ripples = oxyfieldSeedRipples(width, height, 0L)
    return OxyfieldFrame(ripples, ripples[0].startMs + 2600L)
}

/** Live ripples: spawns one every [OxyfieldSpec.SPAWN_INTERVAL_MS] and drops them when they die. */
class OxyfieldRippleField(
    private val random: Random,
    private val width: Int,
    private val height: Int,
) {
    private val scale: Float = if (width > 0) width / OxyfieldSpec.REF_WIDTH else 0f
    private val live = ArrayList<Ripple>(OxyfieldSpec.MAX_RIPPLES)
    private var lastSpawnMs: Long = 0L

    /** Current ripples, oldest first. Read-only view; do not hold across [advance]. */
    val ripples: List<Ripple> get() = live

    /** Starts over with the two seed ripples; the first [advance] spawns a third (as in the mockup). */
    fun reset(nowMs: Long) {
        live.clear()
        live.addAll(oxyfieldSeedRipples(width, height, nowMs))
        lastSpawnMs = nowMs - OxyfieldSpec.SPAWN_INTERVAL_MS - 1L
    }

    /** Drops dead ripples, then spawns one if more than the spawn interval has passed. */
    fun advance(nowMs: Long) {
        var i = live.size - 1
        while (i >= 0) {
            if (live[i].isExpired(nowMs)) live.removeAt(i)
            i--
        }
        if (nowMs - lastSpawnMs > OxyfieldSpec.SPAWN_INTERVAL_MS) {
            lastSpawnMs = nowMs
            spawn(nowMs)
        }
    }

    private fun spawn(nowMs: Long) {
        // Draw order matches the mockup's object literal: x, y, speed.
        val rx = random.nextFloat()
        val ry = random.nextFloat()
        val rv = random.nextFloat()
        if (live.size >= OxyfieldSpec.MAX_RIPPLES) live.removeAt(0)
        live.add(
            Ripple(
                x = width * ((200f + rx * 1520f) / OxyfieldSpec.REF_WIDTH),
                y = height * ((120f + ry * 840f) / OxyfieldSpec.REF_HEIGHT),
                startMs = nowMs,
                speed = (OxyfieldSpec.SPEED_MIN + rv * OxyfieldSpec.SPEED_SPAN) * scale,
                width = OxyfieldSpec.RIPPLE_WIDTH * scale,
                lifeSec = OxyfieldSpec.RIPPLE_LIFE_SEC,
            ),
        )
    }
}

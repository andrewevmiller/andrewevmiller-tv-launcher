package com.andrewevmiller.tvlauncher.home

import kotlin.math.exp
import kotlin.math.hypot

// The featured band's dot-field maths. Kept free of Android and Compose imports so tools/purecheck compiles it
// and DotEnergyTest runs there as well as under Gradle; FeatureBand.kt draws with it.

internal class Ripple(val x: Float, val y: Float, val t0: Float, val speed: Float, val width: Float, val life: Float)

/** How lit a dot at ([x], [y]) is at time [t] (seconds), 0 to 1. Ripples farther than 4 widths from a dot add under 0.04%, so they are skipped. */
internal fun dotEnergy(x: Float, y: Float, t: Float, ripples: List<Ripple>): Float {
    var e = 0f
    for (r in ripples) {
        val age = t - r.t0
        val fade = 1 - age / r.life
        if (fade <= 0f) continue
        val diff = hypot(x - r.x, y - r.y) - age * r.speed
        if (diff > 4 * r.width || diff < -4 * r.width) continue
        e += exp(-(diff * diff) / (2 * r.width * r.width)) * fade
    }
    return minOf(1f, e)
}

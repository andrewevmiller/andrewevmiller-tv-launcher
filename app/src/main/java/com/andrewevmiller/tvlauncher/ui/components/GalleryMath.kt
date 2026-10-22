package com.andrewevmiller.tvlauncher.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Rest tilt of a gallery print, in degrees. It straightens to 0 on focus (see `Modifier.fieldTile`). */
const val GalleryTiltDegrees = 0.8f

/** Alternating rest tilt by position, as in the mockup: even indices lean -0.8 deg, odd +0.8 deg. */
fun tiltFor(index: Int): Float = if ((index and 1) == 0) -GalleryTiltDegrees else GalleryTiltDegrees

/**
 * CSS-style gradient angle (0 = toward the top, clockwise) for the placeholder plate of tile [index].
 * The mockup uses `140 + 23 * index` degrees so neighbouring prints don't look identical; the index is
 * wrapped so the angle stays bounded.
 */
fun plateAngle(index: Int): Float = 140f + 23f * index.mod(16)

/** Start and end of a gradient line in a box, in the box's own pixel space. */
data class GradientLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * The CSS `linear-gradient(<angle>, ...)` gradient line for a [width] x [height] box: it runs through
 * the centre, and is long enough that the corners take the first and last colour exactly.
 */
fun gradientLine(width: Float, height: Float, angleDeg: Float): GradientLine {
    val a = angleDeg.toDouble() * PI / 180.0
    val dx = sin(a).toFloat()
    val dy = (-cos(a)).toFloat()
    val len = abs(width * dx) + abs(height * dy)
    val cx = width / 2f
    val cy = height / 2f
    return GradientLine(
        x0 = cx - dx * len / 2f,
        y0 = cy - dy * len / 2f,
        x1 = cx + dx * len / 2f,
        y1 = cy + dy * len / 2f,
    )
}

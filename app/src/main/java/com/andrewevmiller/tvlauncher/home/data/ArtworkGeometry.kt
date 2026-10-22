package com.andrewevmiller.tvlauncher.home.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure Kotlin on purpose (no android.* imports) so the maths can be unit-tested on the plain JVM.

/** Destination rectangle for a drawable inside a bitmap. May extend past the bitmap when covering. */
data class FitRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where to draw a [srcW] x [srcH] image inside a [dstW] x [dstH] bitmap, centered, aspect ratio kept.
 *
 * [cover] = true scales until the destination is filled (the overflow is cropped by the bitmap edge),
 * used for 16:9 banners. [cover] = false scales until the whole image fits (letterboxed), used for icons.
 * A source with no usable intrinsic size (zero or negative) fills the destination.
 */
fun fitRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int, cover: Boolean): FitRect {
    if (srcW <= 0 || srcH <= 0) return FitRect(0, 0, dstW, dstH)
    val sx = dstW.toDouble() / srcW
    val sy = dstH.toDouble() / srcH
    val scale = if (cover) max(sx, sy) else min(sx, sy)
    val w = (srcW * scale).roundToInt()
    val h = (srcH * scale).roundToInt()
    val left = (dstW - w) / 2
    val top = (dstH - h) / 2
    return FitRect(left, top, left + w, top + h)
}

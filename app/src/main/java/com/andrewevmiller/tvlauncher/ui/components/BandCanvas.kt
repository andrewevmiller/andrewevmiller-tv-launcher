package com.andrewevmiller.tvlauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.andrewevmiller.tvlauncher.theme.Field

/**
 * The feature band's small dot field: dots on a 9dp grid, a slow ripple brightening them
 * `line` -> `muted` -> `accent` (the mockup's band canvas, which is the Oxyfield screensaver in miniature).
 *
 * Capped at about 30 fps. With reduced motion on it draws one static frame (the ring as it looks 1.4 s
 * in) and runs no animation at all. The animation lives only while this composable is in the composition,
 * and time is read in the draw phase, so frames cause redraws, not recompositions.
 * This is the one "other emphasis" the home screen spends its accent on besides the focused element.
 */
@Composable
fun BandCanvas(modifier: Modifier = Modifier.fillMaxSize()) {
    val colors = Field.colors
    val reduced = Field.reducedMotion
    var timeSec by remember { mutableFloatStateOf(BandField.STILL_TIME_SEC) }

    LaunchedEffect(reduced) {
        if (reduced) {
            timeSec = BandField.STILL_TIME_SEC
        } else {
            val cap = BandFrameCap()
            var startNanos = -1L
            while (true) {
                withFrameNanos { now ->
                    if (startNanos < 0L) startNanos = now
                    if (cap.accept(now)) {
                        timeSec = BandField.timeForElapsed(now - startNanos)
                    }
                }
            }
        }
    }

    val rest = colors.line
    val mid = colors.muted
    val peak = colors.accent
    Canvas(modifier) {
        val unit = 1.dp.toPx()
        val widthDp = size.width / unit
        val heightDp = size.height / unit
        val ripples = BandField.activeRipples(timeSec)
        val step = BandField.SPACING_DP
        var y = step / 2f
        while (y < heightDp) {
            var x = step / 2f
            while (x < widthDp) {
                val e = BandField.energyAt(x, y, widthDp, heightDp, ripples)
                val color = if (e < 0.5f) {
                    lerp(rest, mid, e * 2f)
                } else {
                    lerp(mid, peak, (e - 0.5f) * 2f)
                }
                drawCircle(
                    color = color,
                    radius = BandField.dotRadiusDp(e) * unit,
                    center = Offset(x * unit, y * unit),
                )
                x += step
            }
            y += step
        }
    }
}

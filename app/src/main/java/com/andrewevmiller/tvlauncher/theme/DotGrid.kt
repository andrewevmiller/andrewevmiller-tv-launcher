package com.andrewevmiller.tvlauncher.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp

/**
 * The dot-field paper texture (§4): 1px dots on a regular grid, drawn behind content.
 * Points are computed once per size and drawn in a single call.
 *
 * TV panels often crush near-black, so [color] may need a brighter value than `line`
 * on some sets; tune it on the real screen rather than trusting the hex.
 */
fun Modifier.dotGrid(
    color: Color,
    spacing: Dp = FieldDimens.dotSpacing,
    radius: Dp = FieldDimens.dotRadius,
): Modifier = drawWithCache {
    val step = spacing.toPx()
    val bounds = size; val stroke = radius.toPx() * 2f
    val points = buildList {
        var y = step / 2f
        while (y < bounds.height) {
            var x = step / 2f
            while (x < bounds.width) {
                add(Offset(x, y))
                x += step
            }
            y += step
        }
    }
    onDrawBehind {
        drawPoints(points, PointMode.Points, color, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

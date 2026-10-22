package com.andrewevmiller.tvlauncher.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The dot-field paper texture (§4): 1px dots on a regular grid, drawn behind content.
 *
 * When the spacing is a whole number of pixels (it is on the TV: 26dp at 2 px per dp) one cell with its dot
 * is drawn once into a small bitmap and repeated as a shader, so each frame is a single rectangle fill
 * instead of hundreds of round points. Otherwise the points are computed once per size and drawn in one call.
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
    val stroke = radius.toPx() * 2f
    val tile = dotTile(step, stroke, color)
    if (tile != null) {
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind { drawRect(brush) }
    } else {
        val bounds = size
        val points = dotPoints(step, bounds)
        onDrawBehind { drawPoints(points, PointMode.Points, color, strokeWidth = stroke, cap = StrokeCap.Round) }
    }
}

/** One grid cell with its dot at the centre, or null when [step] is not a whole number of pixels. */
internal fun dotTile(step: Float, stroke: Float, color: Color): ImageBitmap? {
    val cell = step.roundToInt()
    if (cell < 2 || abs(step - cell) > 1e-3f) return null
    val bitmap = ImageBitmap(cell, cell)
    val paint = Paint().apply { this.color = color; isAntiAlias = true }
    Canvas(bitmap).drawCircle(Offset(cell / 2f, cell / 2f), stroke / 2f, paint)
    return bitmap
}

/** Dot centres on the grid, half a step in from the top-left, for every dot whose centre is inside [bounds]. */
internal fun dotPoints(step: Float, bounds: Size): List<Offset> = buildList {
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

internal fun DrawScope.drawDotsAsPoints(step: Float, stroke: Float, color: Color) =
    drawPoints(dotPoints(step, size), PointMode.Points, color, strokeWidth = stroke, cap = StrokeCap.Round)

internal fun DrawScope.drawDotsTiled(tile: ImageBitmap) =
    drawRect(ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)))

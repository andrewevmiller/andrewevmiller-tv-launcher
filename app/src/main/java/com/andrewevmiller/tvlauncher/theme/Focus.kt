package com.andrewevmiller.tvlauncher.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The launcher's single focus treatment, from the site's hover states:
 * rest = hairline `line` border (and an optional slight tilt, like the gallery prints);
 * focused = accent border, small scale-up, tilt straightens.
 *
 * Must come before the focus target in the chain, e.g.
 * `Modifier.fieldFocus().clickable { }`, or use [fieldTile] which does both.
 *
 * The animation progress is read only while the layer and the border are drawn, never during
 * composition, so moving focus does not recompose the tile.
 */
fun Modifier.fieldFocus(
    shape: Shape = FieldShapes.tile,
    restTilt: Float = 0f,
    focusedScale: Float = 1.06f,
    bleed: Boolean = false,
): Modifier = composed {
    val colors = LocalFieldColors.current
    val reduced = LocalReducedMotion.current
    val sound = com.andrewevmiller.tvlauncher.sound.LocalFieldSound.current
    // 0 at rest, 1 focused. Deliberately not read in composition.
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusFlag() }
    this
        .onFocusChanged {
            val now = it.isFocused || it.hasFocus
            if (now == focus.on) return@onFocusChanged
            focus.on = now
            if (now) sound?.tick()
            scope.launch {
                progress.animateTo(
                    targetValue = if (now) 1f else 0f,
                    animationSpec = if (reduced) snap<Float>() else tween<Float>(durationMillis = FOCUS_MS),
                )
            }
        }
        .graphicsLayer {
            val t = progress.value
            val s = focusScale(t, focusedScale)
            scaleX = s
            scaleY = s
            rotationZ = focusTilt(t, restTilt)
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawWithContent {
                drawContent()
                val t = progress.value
                drawFieldBorder(outline, borderColor(t, bleed, colors.line, colors.accent), borderWidth(t, bleed))
            }
        }
}

/** A focusable, D-pad-clickable tile with the standard focus treatment. */
fun Modifier.fieldTile(
    onClick: () -> Unit,
    shape: Shape = FieldShapes.tile,
    restTilt: Float = 0f,
    focusedScale: Float = 1.06f,
    bleed: Boolean = false,
): Modifier = composed {
    this
        .fieldFocus(shape, restTilt, focusedScale, bleed)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
}

private const val FOCUS_MS = 160

/** Whether the tile currently has focus. A plain holder, not state, so changing it never recomposes. */
private class FocusFlag { var on = false }

// --- the treatment as functions of progress t (0 = rest, 1 = focused) -------------------------

internal fun focusScale(t: Float, focusedScale: Float): Float = 1f + (focusedScale - 1f) * t

internal fun focusTilt(t: Float, restTilt: Float): Float = restTilt * (1f - t)

/** Full-bleed art has no resting frame: its border starts hair-thin and invisible and only appears with focus. */
internal fun borderWidth(t: Float, bleed: Boolean): Dp = lerp(if (bleed) 0.5.dp else 1.dp, 3.dp, t)

internal fun borderColor(t: Float, bleed: Boolean, line: Color, accent: Color): Color =
    lerp(if (bleed) Color.Transparent else line, accent, t)

/** Compose's own rule for `Modifier.border`: whole pixels, and never more than half the smaller side. */
internal fun borderStrokePx(widthPx: Float, minDimension: Float): Float = min(ceil(widthPx), ceil(minDimension / 2))

/**
 * Draws the same border `Modifier.border(width, color, shape)` would, but with width and color
 * supplied at draw time so they can animate without recomposing. Follows foundation's Border.kt:
 * the stroke sits inside the bounds, corner radii shrink by half the stroke, and a stroke thicker
 * than the tile fills it.
 */
private fun DrawScope.drawFieldBorder(outline: Outline, color: Color, width: Dp) {
    if (color.alpha == 0f || size.minDimension <= 0f) return
    val stroke = borderStrokePx(width.toPx(), size.minDimension)
    val half = stroke / 2
    val inner = Size(size.width - stroke, size.height - stroke)
    val fillArea = stroke * 2 > size.minDimension
    when {
        outline is Outline.Rectangle ->
            if (fillArea) drawRect(color) else drawRect(color, Offset(half, half), inner, style = Stroke(stroke))

        outline is Outline.Rounded && outline.roundRect.isSimple -> {
            val corner = outline.roundRect.topLeftCornerRadius
            when {
                fillArea -> drawRoundRect(color, cornerRadius = corner)
                // Corner tighter than half the stroke: the inside edge is square, so fill and cut out the middle.
                corner.x < half ->
                    clipRect(stroke, stroke, size.width - stroke, size.height - stroke, ClipOp.Difference) {
                        drawRoundRect(color, cornerRadius = corner)
                    }
                else -> drawRoundRect(
                    color, Offset(half, half), inner,
                    CornerRadius(max(corner.x - half, 0f), max(corner.y - half, 0f)),
                    style = Stroke(stroke),
                )
            }
        }

        else -> {
            // Uneven corners or a free-form shape: stroke the edge twice as wide and keep only the inside half.
            val path = when (outline) {
                is Outline.Generic -> outline.path
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
            }
            clipPath(path) { drawPath(path, color, style = Stroke(stroke * 2)) }
        }
    }
}

package com.andrewevmiller.tvlauncher.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/**
 * The launcher's single focus treatment, from the site's hover states:
 * rest = hairline `line` border (and an optional slight tilt, like the gallery prints);
 * focused = accent border, small scale-up, tilt straightens.
 *
 * Must come before the focus target in the chain, e.g.
 * `Modifier.fieldFocus().clickable { }`, or use [fieldTile] which does both.
 */
fun Modifier.fieldFocus(
    shape: Shape = FieldShapes.tile,
    restTilt: Float = 0f,
    focusedScale: Float = 1.06f,
): Modifier = composed {
    val colors = LocalFieldColors.current
    val reduced = LocalReducedMotion.current
    val sound = com.andrewevmiller.tvlauncher.sound.LocalFieldSound.current
    var focused by remember { mutableStateOf(false) }
    val t by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(durationMillis = 160),
        label = "fieldFocus",
    )
    this
        .onFocusChanged {
            val now = it.isFocused || it.hasFocus
            if (now && !focused) sound?.tick()
            focused = now
        }
        .graphicsLayer {
            val s = 1f + (focusedScale - 1f) * t
            scaleX = s
            scaleY = s
            rotationZ = restTilt * (1f - t)
        }
        .border(
            width = lerp(1.dp, 3.dp, t),
            color = lerp(colors.line, colors.accent, t),
            shape = shape,
        )
}

/** A focusable, D-pad-clickable tile with the standard focus treatment. */
fun Modifier.fieldTile(
    onClick: () -> Unit,
    shape: Shape = FieldShapes.tile,
    restTilt: Float = 0f,
    focusedScale: Float = 1.06f,
): Modifier = composed {
    this
        .fieldFocus(shape, restTilt, focusedScale)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
}

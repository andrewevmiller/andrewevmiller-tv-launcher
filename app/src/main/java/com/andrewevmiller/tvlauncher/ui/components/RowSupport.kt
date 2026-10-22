package com.andrewevmiller.tvlauncher.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * Room around a horizontal list's items so the focused item's 1.06 scale-up, tilt and 3dp border are
 * never clipped by the lazy list's bounds. Used as `contentPadding` together with [focusBleed].
 */
internal val FocusBleed = 8.dp

/**
 * Lets this node paint [horizontal] x [vertical] beyond the space it takes in its parent's layout
 * (a "negative padding"). A `LazyRow` with `contentPadding = FocusBleed` plus `focusBleed()` therefore
 * keeps its first item aligned with the section hairline and label, like the mockup's
 * `margin: -8px; padding: 8px` viewport, while still having room for the focus effect.
 */
internal fun Modifier.layoutBleed(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * h, vertical = 2 * v))
    layout(
        (placeable.width - 2 * h).coerceAtLeast(0),
        (placeable.height - 2 * v).coerceAtLeast(0),
    ) {
        placeable.place(-h, -v)
    }
}

internal fun Modifier.focusBleed(): Modifier = layoutBleed(FocusBleed, FocusBleed)

/**
 * Handles LEFT/RIGHT at the ends of a row. Put it before the focusable (`fieldTile`) in the chain so it
 * sees the key events of the focused item. When the item at [index] of [count] cannot move focus in the
 * pressed direction, the key is consumed and [onEdge] runs once per press (not on key repeat), so the
 * edge sound plays instead of focus jumping to an unrelated neighbour. Every other key, and every
 * LEFT/RIGHT that can move, is left to the default focus traversal.
 */
internal fun Modifier.rowEdgeKeys(index: Int, count: Int, onEdge: () -> Unit): Modifier =
    onKeyEvent { event ->
        val step = when (event.key) {
            Key.DirectionLeft -> RowStep.LEFT
            Key.DirectionRight -> RowStep.RIGHT
            else -> null
        }
        if (step == null || event.type != KeyEventType.KeyDown || !isRowEdge(index, count, step)) {
            false
        } else {
            if (event.nativeKeyEvent.repeatCount == 0) onEdge()
            true
        }
    }

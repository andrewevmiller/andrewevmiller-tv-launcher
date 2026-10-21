package com.andrewevmiller.tvlauncher.ui.components

/** Horizontal D-pad direction inside a single row of focusable items. */
enum class RowStep { LEFT, RIGHT }

/**
 * True when a [step] from the item at [index] in a row of [count] items cannot move focus
 * inside the row (first item going left, last item going right). The row then consumes the key
 * and plays `FieldSound.edge()` instead of letting focus jump to some unrelated neighbour.
 *
 * An empty or one-item row is an edge in both directions.
 */
fun isRowEdge(index: Int, count: Int, step: RowStep): Boolean = when (step) {
    RowStep.LEFT -> index <= 0
    RowStep.RIGHT -> index >= count - 1
}

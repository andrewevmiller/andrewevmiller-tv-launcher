package com.andrewevmiller.tvlauncher.home.apps

/*
 * Pure D-pad rules for the app grid (BUILD_PLAN section 5.4). No Android or Compose types, so these are
 * unit-tested by tools/purecheck. The Compose layer only translates key events to GridDirection and
 * acts on the GridMove it gets back.
 *
 * Cells are numbered row by row: with 4 columns, index 5 is row 1, column 1. The last row may be short.
 */

enum class GridDirection { LEFT, RIGHT, UP, DOWN }

/** What a D-pad press does to focus. */
sealed interface GridMove {
    /** Focus moves to [index]. */
    data class Stay(val index: Int) : GridMove

    /** Left or right ran into the end of a grid row: nothing moves (the caller plays `FieldSound.edge()`). */
    data object Edge : GridMove

    /** Up from the first grid row: focus leaves the grid upwards (to the chips row). */
    data object ExitUp : GridMove

    /** Down from the last grid row: focus leaves the grid downwards (to the next section). */
    data object ExitDown : GridMove
}

fun columnOf(index: Int, columns: Int): Int {
    require(columns > 0) { "columns must be positive" }
    return index.coerceAtLeast(0) % columns
}

fun rowOf(index: Int, columns: Int): Int {
    require(columns > 0) { "columns must be positive" }
    return index.coerceAtLeast(0) / columns
}

/**
 * One D-pad press on the tile at [index] of a grid with [count] cells in [columns] columns.
 *
 * - Left and right stay inside the grid row and never wrap: at a row's end the result is [GridMove.Edge]
 *   (including the end of a short last row).
 * - Up and down keep the column in the adjacent row. If that row is shorter, focus lands on its last tile.
 * - Up from the first row is [GridMove.ExitUp]; down from the last row is [GridMove.ExitDown].
 *
 * An out-of-range [index] is clamped into the grid; an empty grid has nowhere to go ([GridMove.Edge]).
 */
fun gridMove(index: Int, count: Int, columns: Int, direction: GridDirection): GridMove {
    require(columns > 0) { "columns must be positive" }
    if (count <= 0) return GridMove.Edge
    val i = index.coerceIn(0, count - 1)
    val row = i / columns
    val col = i % columns
    val lastRow = (count - 1) / columns
    return when (direction) {
        GridDirection.LEFT ->
            if (col == 0) GridMove.Edge else GridMove.Stay(i - 1)
        GridDirection.RIGHT ->
            if (col == columns - 1 || i == count - 1) GridMove.Edge else GridMove.Stay(i + 1)
        GridDirection.UP ->
            if (row == 0) GridMove.ExitUp else GridMove.Stay(i - columns)
        GridDirection.DOWN ->
            // The row below is only ever short when it is the last one; then land on its last tile.
            if (row >= lastRow) GridMove.ExitDown else GridMove.Stay(minOf(i + columns, count - 1))
    }
}

/**
 * Where focus lands when it enters the grid from outside: the top row when [fromTop] (coming from above),
 * else the bottom row. The column is the one nearest [preferredColumn] (the column of the previously focused
 * item), clamped to the length of that row. Returns -1 for an empty grid.
 */
fun entryIndex(count: Int, columns: Int, preferredColumn: Int, fromTop: Boolean): Int {
    require(columns > 0) { "columns must be positive" }
    if (count <= 0) return -1
    val rowStart = if (fromTop) 0 else ((count - 1) / columns) * columns
    val rowLength = minOf(columns, count - rowStart)
    return rowStart + preferredColumn.coerceIn(0, rowLength - 1)
}

/**
 * Focus survival after the list changed (pin, unpin, hide, install, uninstall, filter): the tile now at the
 * previous index, clamped to the new size. Returns -1 when the grid is empty.
 */
fun clampFocus(previousIndex: Int, newCount: Int): Int =
    if (newCount <= 0) -1 else previousIndex.coerceIn(0, newCount - 1)

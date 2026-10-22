package com.andrewevmiller.tvlauncher.home.apps

/*
 * Pure reorder rules for edit mode (BUILD_PLAN section 5.4). No Android or Compose types.
 */

/**
 * Moves the item at [from] so it ends up at index [to] (clamped into the list). Same semantics as
 * `AppsRepository.move`: remove the item, then insert it at [to] in the shorter list. Returns [list] itself
 * when nothing moves (including an out-of-range [from]); never mutates its input.
 */
fun <T> reorder(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices) return list
    val target = to.coerceIn(0, list.size - 1)
    if (target == from) return list
    val out = list.toMutableList()
    val item = out.removeAt(from)
    out.add(target, item)
    return out
}

/**
 * Where the lifted tile at [index] goes on a D-pad press in edit mode. Returns [index] when it cannot move.
 *
 * - Left and right swap with the neighbour (index -1 / +1; this runs on across grid rows, like the list).
 * - Up moves one grid row back (-[columns]); it cannot move from the first row.
 * - Down moves one grid row on (+[columns]); into a shorter last row it lands on the last tile. It cannot
 *   move from the last row.
 */
fun editTarget(index: Int, count: Int, columns: Int, direction: GridDirection): Int {
    require(columns > 0) { "columns must be positive" }
    if (count <= 0 || index !in 0 until count) return index
    val row = index / columns
    val lastRow = (count - 1) / columns
    return when (direction) {
        GridDirection.LEFT -> if (index > 0) index - 1 else index
        GridDirection.RIGHT -> if (index < count - 1) index + 1 else index
        GridDirection.UP -> if (row > 0) index - columns else index
        GridDirection.DOWN -> if (row < lastRow) minOf(index + columns, count - 1) else index
    }
}

/**
 * Maps a slot in a filtered view back to the full list, for `AppsSection` when a category chip is active:
 * to put the moved item where `shown[shownIndex]` currently sits in the view, move it to this index in the
 * full list. Returns -1 when [shownIndex] is outside [shown] or the item is not in [full].
 * (Moving to another item's index gives the same relative order in the filtered view.)
 */
fun <T> fullIndexOfShown(full: List<T>, shown: List<T>, shownIndex: Int): Int {
    val slot = shown.getOrNull(shownIndex) ?: return -1
    return full.indexOf(slot)
}

package com.andrewevmiller.tvlauncher.home.apps

import com.andrewevmiller.tvlauncher.home.apps.GridDirection.DOWN
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.LEFT
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.RIGHT
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUILD_PLAN 5.4 D-pad rules. The Home grid with 6 pinned apps plus the "All apps" cell is 7 cells in 4 columns
 * (rows 0-3 and 4-6); 9 pinned apps plus the cell is 10 cells (rows 0-3, 4-7, 8-9).
 */
class GridNavigationTest {
    private val cols = 4

    private fun move(index: Int, count: Int, dir: GridDirection) = gridMove(index, count, cols, dir)

    // --- left / right: stay in the row, never wrap ---------------------------------------------------------

    @Test
    fun rightMovesWithinTheRow() {
        assertEquals(GridMove.Stay(1), move(0, 7, RIGHT))
        assertEquals(GridMove.Stay(3), move(2, 7, RIGHT))
        assertEquals(GridMove.Stay(5), move(4, 7, RIGHT))
    }

    @Test
    fun leftMovesWithinTheRow() {
        assertEquals(GridMove.Stay(0), move(1, 7, LEFT))
        assertEquals(GridMove.Stay(4), move(5, 7, LEFT))
    }

    @Test
    fun leftAtTheStartOfARowIsAnEdgeAndDoesNotWrapToThePreviousRow() {
        assertEquals(GridMove.Edge, move(0, 7, LEFT))
        assertEquals(GridMove.Edge, move(4, 7, LEFT))
        assertEquals(GridMove.Edge, move(8, 10, LEFT))
    }

    @Test
    fun rightAtTheEndOfARowIsAnEdgeAndDoesNotWrapToTheNextRow() {
        assertEquals(GridMove.Edge, move(3, 7, RIGHT))
        assertEquals(GridMove.Edge, move(7, 10, RIGHT))
    }

    @Test
    fun rightAtTheEndOfAShortLastRowIsAnEdge() {
        // 7 cells: last row is 4,5,6. 10 cells: last row is 8,9.
        assertEquals(GridMove.Edge, move(6, 7, RIGHT))
        assertEquals(GridMove.Edge, move(9, 10, RIGHT))
        assertEquals(GridMove.Stay(9), move(8, 10, RIGHT))
    }

    // --- up / down: same column in the adjacent row --------------------------------------------------------

    @Test
    fun downKeepsTheColumn() {
        assertEquals(GridMove.Stay(4), move(0, 7, DOWN))
        assertEquals(GridMove.Stay(5), move(1, 7, DOWN))
        assertEquals(GridMove.Stay(9), move(5, 10, DOWN))
    }

    @Test
    fun upKeepsTheColumn() {
        assertEquals(GridMove.Stay(0), move(4, 7, UP))
        assertEquals(GridMove.Stay(1), move(5, 7, UP))
        assertEquals(GridMove.Stay(6), move(10, 14, UP))
    }

    @Test
    fun downIntoAShorterRowLandsOnItsLastTile() {
        // 7 cells: column 3 has no tile in row 1, so land on the last tile (6, the All apps cell).
        assertEquals(GridMove.Stay(6), move(3, 7, DOWN))
        assertEquals(GridMove.Stay(6), move(2, 7, DOWN))
        // 10 cells: from 7 (row 1, column 3) down lands on 9.
        assertEquals(GridMove.Stay(9), move(7, 10, DOWN))
        assertEquals(GridMove.Stay(9), move(6, 10, DOWN))
    }

    @Test
    fun downIntoAShortRowKeepsTheColumnWhenTheTileExists() {
        assertEquals(GridMove.Stay(8), move(4, 10, DOWN))
        assertEquals(GridMove.Stay(9), move(5, 10, DOWN))
    }

    @Test
    fun upFromAShortLastRowGoesToTheSameColumnAbove() {
        assertEquals(GridMove.Stay(4), move(8, 10, UP))
        assertEquals(GridMove.Stay(5), move(9, 10, UP))
    }

    // --- leaving the grid ----------------------------------------------------------------------------------

    @Test
    fun upFromTheFirstRowExitsUp() {
        for (i in 0..3) assertEquals(GridMove.ExitUp, move(i, 7, UP))
        // A single short row too.
        assertEquals(GridMove.ExitUp, move(1, 2, UP))
    }

    @Test
    fun downFromTheLastRowExitsDown() {
        for (i in 4..6) assertEquals(GridMove.ExitDown, move(i, 7, DOWN))
        for (i in 8..9) assertEquals(GridMove.ExitDown, move(i, 10, DOWN))
        assertEquals(GridMove.ExitDown, move(0, 3, DOWN))
    }

    @Test
    fun aSingleTileHasEdgesSidewaysAndExitsVertically() {
        assertEquals(GridMove.Edge, move(0, 1, LEFT))
        assertEquals(GridMove.Edge, move(0, 1, RIGHT))
        assertEquals(GridMove.ExitUp, move(0, 1, UP))
        assertEquals(GridMove.ExitDown, move(0, 1, DOWN))
    }

    @Test
    fun anEmptyGridAndOutOfRangeIndicesDoNotCrash() {
        for (d in GridDirection.values()) assertEquals(GridMove.Edge, move(0, 0, d))
        assertEquals(GridMove.Stay(5), move(99, 7, LEFT)) // clamped to the last tile (6), then one left
    }

    @Test
    fun oneColumnGridIsAVerticalList() {
        assertEquals(GridMove.Edge, gridMove(1, 5, 1, LEFT))
        assertEquals(GridMove.Edge, gridMove(1, 5, 1, RIGHT))
        assertEquals(GridMove.Stay(2), gridMove(1, 5, 1, DOWN))
        assertEquals(GridMove.Stay(0), gridMove(1, 5, 1, UP))
        assertEquals(GridMove.ExitDown, gridMove(4, 5, 1, DOWN))
    }

    // --- invariants over every small grid ------------------------------------------------------------------

    @Test
    fun invariantsHoldForEverySmallGrid() {
        for (columns in 1..6) for (count in 1..14) for (index in 0 until count) for (dir in GridDirection.values()) {
            val row = rowOf(index, columns)
            val col = columnOf(index, columns)
            val lastRow = rowOf(count - 1, columns)
            val m = gridMove(index, count, columns, dir)
            val where = "columns=$columns count=$count index=$index dir=$dir"
            when (dir) {
                LEFT, RIGHT -> {
                    when (m) {
                        is GridMove.Stay -> {
                            assertEquals(where, row, rowOf(m.index, columns))
                            assertEquals(where, if (dir == LEFT) index - 1 else index + 1, m.index)
                            assertTrue(where, m.index in 0 until count)
                        }
                        GridMove.Edge -> {
                            val atEnd = if (dir == LEFT) col == 0 else (col == columns - 1 || index == count - 1)
                            assertTrue(where, atEnd)
                        }
                        else -> throw AssertionError("$where: unexpected $m")
                    }
                }
                UP -> {
                    if (row == 0) assertEquals(where, GridMove.ExitUp, m)
                    else {
                        m as GridMove.Stay
                        assertEquals(where, row - 1, rowOf(m.index, columns))
                        assertEquals(where, col, columnOf(m.index, columns))
                    }
                }
                DOWN -> {
                    if (row == lastRow) assertEquals(where, GridMove.ExitDown, m)
                    else {
                        m as GridMove.Stay
                        assertEquals(where, row + 1, rowOf(m.index, columns))
                        assertTrue(where, m.index in 0 until count)
                        // Same column, unless the row is shorter: then the last tile.
                        assertTrue(where, columnOf(m.index, columns) == col || m.index == count - 1)
                    }
                }
            }
        }
    }

    @Test
    fun everyTileIsReachableFromTheFirstWithoutLeavingTheGrid() {
        for (columns in 1..6) for (count in 1..14) {
            val seen = mutableSetOf(0)
            val queue = ArrayDeque(listOf(0))
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (d in GridDirection.values()) {
                    val m = gridMove(at, count, columns, d)
                    if (m is GridMove.Stay && seen.add(m.index)) queue.add(m.index)
                }
            }
            assertEquals("columns=$columns count=$count", count, seen.size)
        }
    }

    // --- entry from above / below --------------------------------------------------------------------------

    @Test
    fun enteringFromAboveLandsOnTheTopRowAtTheNearestColumn() {
        assertEquals(0, entryIndex(7, cols, 0, fromTop = true))
        assertEquals(2, entryIndex(7, cols, 2, fromTop = true))
        assertEquals(3, entryIndex(7, cols, 3, fromTop = true))
    }

    @Test
    fun enteringFromBelowLandsOnTheBottomRowAtTheNearestColumn() {
        // 7 cells: bottom row is 4,5,6.
        assertEquals(4, entryIndex(7, cols, 0, fromTop = false))
        assertEquals(5, entryIndex(7, cols, 1, fromTop = false))
        assertEquals(6, entryIndex(7, cols, 2, fromTop = false))
        // Preferred column 3 does not exist in the short bottom row: clamp to its last tile.
        assertEquals(6, entryIndex(7, cols, 3, fromTop = false))
        // 10 cells: bottom row is 8,9.
        assertEquals(9, entryIndex(10, cols, 3, fromTop = false))
        // A full last row keeps the column.
        assertEquals(7, entryIndex(8, cols, 3, fromTop = false))
    }

    @Test
    fun enteringAShortSingleRowClampsToItsLength() {
        assertEquals(1, entryIndex(2, cols, 3, fromTop = true))
        assertEquals(1, entryIndex(2, cols, 3, fromTop = false))
        assertEquals(0, entryIndex(1, cols, 2, fromTop = true))
    }

    @Test
    fun entryClampsNegativeColumnsAndHandlesAnEmptyGrid() {
        assertEquals(0, entryIndex(7, cols, -3, fromTop = true))
        assertEquals(4, entryIndex(7, cols, -3, fromTop = false))
        assertEquals(-1, entryIndex(0, cols, 1, fromTop = true))
    }

    // --- rows, columns, focus survival ---------------------------------------------------------------------

    @Test
    fun rowAndColumnOfAnIndex() {
        assertEquals(0, rowOf(3, cols)); assertEquals(3, columnOf(3, cols))
        assertEquals(1, rowOf(4, cols)); assertEquals(0, columnOf(4, cols))
        assertEquals(2, rowOf(9, cols)); assertEquals(1, columnOf(9, cols))
    }

    @Test
    fun focusSurvivesListChangesByStayingAtTheSameIndex() {
        assertEquals(3, clampFocus(3, 7))
        assertEquals(0, clampFocus(0, 1))
    }

    @Test
    fun focusClampsToTheLastTileWhenTheListShrinks() {
        // The focused tile at index 6 (the All apps cell) of 7; after an unpin there are 6 cells.
        assertEquals(5, clampFocus(6, 6))
        assertEquals(0, clampFocus(6, 1))
    }

    @Test
    fun focusIsNoneWhenTheGridIsEmpty() {
        assertEquals(-1, clampFocus(2, 0))
        assertEquals(0, clampFocus(-1, 3))
    }
}

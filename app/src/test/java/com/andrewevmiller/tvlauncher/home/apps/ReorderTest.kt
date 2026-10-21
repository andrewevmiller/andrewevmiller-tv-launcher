package com.andrewevmiller.tvlauncher.home.apps

import com.andrewevmiller.tvlauncher.home.apps.GridDirection.DOWN
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.LEFT
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.RIGHT
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** BUILD_PLAN 5.4 edit mode: reorder, the edit-mode D-pad target, and the filtered-view mapping. */
class ReorderTest {
    private val abc = listOf("a", "b", "c", "d", "e", "f")

    // --- reorder -------------------------------------------------------------------------------------------

    @Test
    fun movesForward() {
        assertEquals(listOf("b", "c", "d", "a", "e", "f"), reorder(abc, 0, 3))
        assertEquals(listOf("a", "b", "c", "d", "f", "e"), reorder(abc, 4, 5))
    }

    @Test
    fun movesBackward() {
        assertEquals(listOf("d", "a", "b", "c", "e", "f"), reorder(abc, 3, 0))
        assertEquals(listOf("a", "c", "b", "d", "e", "f"), reorder(abc, 2, 1))
    }

    @Test
    fun sameIndexReturnsTheListUnchanged() {
        assertSame(abc, reorder(abc, 2, 2))
    }

    @Test
    fun targetIsClampedIntoTheList() {
        assertEquals(listOf("b", "c", "d", "e", "f", "a"), reorder(abc, 0, 99))
        assertEquals(listOf("f", "a", "b", "c", "d", "e"), reorder(abc, 5, -4))
    }

    @Test
    fun outOfRangeSourceAndEmptyListsAreNoOps() {
        assertSame(abc, reorder(abc, -1, 2))
        assertSame(abc, reorder(abc, 6, 2))
        val empty = emptyList<String>()
        assertSame(empty, reorder(empty, 0, 0))
    }

    @Test
    fun doesNotMutateItsInputAndKeepsEveryElement() {
        val input = abc.toList()
        val out = reorder(input, 1, 4)
        assertEquals(abc, input)
        assertEquals(abc.sorted(), out.sorted())
    }

    @Test
    fun matchesTheRepositoryMoveSemantics() {
        // AppsRepository.move: remove the item, then insert it at toIndex (clamped) in the shorter list.
        fun repositoryMove(list: List<String>, key: String, toIndex: Int): List<String> {
            val rest = list - key
            return rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), key) }
        }
        for (from in abc.indices) for (to in -1..7) {
            assertEquals("from=$from to=$to", repositoryMove(abc, abc[from], to), reorder(abc, from, to))
        }
    }

    // --- editTarget ----------------------------------------------------------------------------------------

    // 7 apps in 4 columns: rows are 0-3 and 4-6.
    private fun target(index: Int, dir: GridDirection, count: Int = 7, columns: Int = 4) =
        editTarget(index, count, columns, dir)

    @Test
    fun leftAndRightSwapWithTheNeighbour() {
        assertEquals(2, target(3, LEFT))
        assertEquals(4, target(3, RIGHT)) // runs on into the next grid row, like the list
        assertEquals(3, target(4, LEFT))
    }

    @Test
    fun leftAtTheStartAndRightAtTheEndCannotMove() {
        assertEquals(0, target(0, LEFT))
        assertEquals(6, target(6, RIGHT))
    }

    @Test
    fun upAndDownMoveOneGridRow() {
        assertEquals(1, target(5, UP))
        assertEquals(4, target(0, DOWN))
        assertEquals(5, target(1, DOWN))
    }

    @Test
    fun downIntoAShorterLastRowClampsToTheLastTile() {
        assertEquals(6, target(3, DOWN)) // 3 + 4 = 7 is past the end of 7 apps
        assertEquals(6, target(2, DOWN))
    }

    @Test
    fun upFromTheFirstRowAndDownFromTheLastRowCannotMove() {
        for (i in 0..3) assertEquals(i, target(i, UP))
        for (i in 4..6) assertEquals(i, target(i, DOWN))
    }

    @Test
    fun editTargetStaysInRangeAndDegenerateInputsAreStable() {
        for (columns in 1..5) for (count in 1..12) for (index in 0 until count) for (d in GridDirection.values()) {
            val t = editTarget(index, count, columns, d)
            assertEquals("$columns $count $index $d", true, t in 0 until count)
        }
        assertEquals(0, editTarget(0, 1, 4, DOWN))
        assertEquals(3, editTarget(3, 0, 4, DOWN))
    }

    @Test
    fun oneColumnGridMovesAndSwapsVertically() {
        assertEquals(1, editTarget(2, 5, 1, UP))
        assertEquals(3, editTarget(2, 5, 1, DOWN))
        // Left/right still step by one in the list.
        assertEquals(1, editTarget(2, 5, 1, LEFT))
        assertEquals(3, editTarget(2, 5, 1, RIGHT))
    }

    // --- filtered view -> full list ------------------------------------------------------------------------

    @Test
    fun movingInAFilteredViewKeepsTheViewOrderRight() {
        val full = listOf("a1", "b1", "a2", "b2", "a3")
        val shown = full.filter { it.startsWith("a") } // a1, a2, a3

        fun applyMove(from: Int, toShown: Int): List<String> {
            val toFull = fullIndexOfShown(full, shown, toShown)
            return reorder(full, full.indexOf(shown[from]), toFull)
        }
        for (from in shown.indices) for (to in shown.indices) {
            val moved = applyMove(from, to)
            assertEquals(
                "from=$from to=$to",
                reorder(shown, from, to),
                moved.filter { it.startsWith("a") },
            )
            assertEquals(full.sorted(), moved.sorted())
        }
    }

    @Test
    fun fullIndexOfShownReportsMissingSlots() {
        val full = listOf("a", "b", "c")
        assertEquals(2, fullIndexOfShown(full, listOf("a", "c"), 1))
        assertEquals(-1, fullIndexOfShown(full, listOf("a", "c"), 2))
        assertEquals(-1, fullIndexOfShown(full, listOf("x"), 0))
    }
}

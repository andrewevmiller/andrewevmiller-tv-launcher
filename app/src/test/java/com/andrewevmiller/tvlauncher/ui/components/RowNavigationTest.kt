package com.andrewevmiller.tvlauncher.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowNavigationTest {
    @Test
    fun firstItemIsTheLeftEdgeOnly() {
        assertTrue(isRowEdge(0, 5, RowStep.LEFT))
        assertFalse(isRowEdge(0, 5, RowStep.RIGHT))
    }

    @Test
    fun lastItemIsTheRightEdgeOnly() {
        assertTrue(isRowEdge(4, 5, RowStep.RIGHT))
        assertFalse(isRowEdge(4, 5, RowStep.LEFT))
    }

    @Test
    fun middleItemsAreNeverEdges() {
        for (i in 1..3) {
            assertFalse(isRowEdge(i, 5, RowStep.LEFT))
            assertFalse(isRowEdge(i, 5, RowStep.RIGHT))
        }
    }

    @Test
    fun singleItemRowIsAnEdgeBothWays() {
        assertTrue(isRowEdge(0, 1, RowStep.LEFT))
        assertTrue(isRowEdge(0, 1, RowStep.RIGHT))
    }

    @Test
    fun emptyRowIsAnEdgeBothWays() {
        assertTrue(isRowEdge(0, 0, RowStep.LEFT))
        assertTrue(isRowEdge(0, 0, RowStep.RIGHT))
    }
}

package com.andrewevmiller.tvlauncher.home.apps

import com.andrewevmiller.tvlauncher.home.apps.GridDirection.DOWN
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.LEFT
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.RIGHT
import com.andrewevmiller.tvlauncher.home.apps.GridDirection.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUILD_PLAN 5.4 edit mode as a session: lift, move, drop, cancel. */
class EditSessionTest {
    // 7 apps in 4 columns.
    private val apps = listOf("a", "b", "c", "d", "e", "f", "g")

    private fun start(index: Int) = EditSession.start(apps, index, 4)

    @Test
    fun liftingStartsUnchangedOnTheTile() {
        val s = start(2)
        assertEquals("c", s.item)
        assertEquals(2, s.index)
        assertEquals(2, s.startIndex)
        assertFalse(s.changed)
    }

    @Test
    fun leftAndRightSwapWithTheNeighbour() {
        assertEquals(listOf("a", "c", "b", "d", "e", "f", "g"), start(2).move(LEFT).working)
        assertEquals(listOf("a", "b", "d", "c", "e", "f", "g"), start(2).move(RIGHT).working)
        assertEquals("c", start(2).move(RIGHT).item)
        assertEquals(3, start(2).move(RIGHT).index)
    }

    @Test
    fun upAndDownMoveOneGridRow() {
        val down = start(1).move(DOWN)
        assertEquals(5, down.index)
        assertEquals(listOf("a", "c", "d", "e", "f", "b", "g"), down.working)
        val up = start(5).move(UP)
        assertEquals(1, up.index)
        assertEquals(listOf("a", "f", "b", "c", "d", "e", "g"), up.working)
    }

    @Test
    fun cannotMoveReturnsTheSameSession() {
        val first = start(0)
        assertSame(first, first.move(LEFT))
        assertSame(first, first.move(UP))
        val last = start(6)
        assertSame(last, last.move(RIGHT))
        assertSame(last, last.move(DOWN))
    }

    @Test
    fun knowsWhenTheLiftedTileIsInTheLastRow() {
        assertFalse(start(1).inLastRow)
        assertTrue(start(4).inLastRow)
        assertTrue(start(6).inLastRow)
        assertTrue(EditSession.start(listOf("a", "b"), 0, 4).inLastRow)
    }

    @Test
    fun theLiftedTileKeepsItsIdentityThroughSeveralMoves() {
        var s = start(1)
        // b: a c b d.. -> a c d b.. -> (down, clamped to the last tile) a c d e f g b -> (left) a c d e f b g
        s = s.move(RIGHT).move(RIGHT).move(DOWN).move(LEFT)
        assertEquals("b", s.item)
        assertEquals(5, s.index)
        assertEquals(listOf("a", "c", "d", "e", "f", "b", "g"), s.working)
        assertEquals(s.item, s.working[s.index])
        assertEquals(apps.sorted(), s.working.sorted())
    }

    @Test
    fun movingThereAndBackRestoresTheOriginalOrder() {
        val s = start(2).move(RIGHT).move(LEFT)
        assertEquals(apps, s.working)
        assertFalse(s.changed)
        val s2 = start(1).move(DOWN).move(UP)
        assertEquals(apps, s2.working)
        assertFalse(s2.changed)
    }

    @Test
    fun aMoveIsChangedAndCancellingKeepsTheOriginalOrder() {
        val s = start(0).move(RIGHT).move(RIGHT)
        assertTrue(s.changed)
        assertEquals(2, s.index)
        // Cancel = discard the session and show `original` again; it was never touched.
        assertEquals(apps, s.original)
        assertEquals(0, s.startIndex)
    }

    @Test
    fun dropPersistsTheFinalIndexRelativeToTheOriginalList() {
        val s = start(0).move(DOWN) // 0 -> 4
        // What AppsRepository.move(component, s.index) would produce:
        assertEquals(s.working, reorder(s.original, s.startIndex, s.index))
    }

    @Test
    fun startRejectsAnOutOfRangeIndex() {
        try {
            EditSession.start(apps, 7, 4)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}

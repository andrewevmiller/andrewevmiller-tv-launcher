package com.andrewevmiller.tvlauncher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryMathTest {
    @Test
    fun tiltAlternatesByIndexParity() {
        assertEquals(-0.8f, tiltFor(0), 0f)
        assertEquals(0.8f, tiltFor(1), 0f)
        assertEquals(-0.8f, tiltFor(2), 0f)
        assertEquals(0.8f, tiltFor(3), 0f)
    }

    @Test
    fun tiltIsAlwaysPointEightDegreesInMagnitude() {
        for (i in -6..40) {
            assertEquals(0.8f, Math.abs(tiltFor(i)), 0f)
        }
    }

    @Test
    fun neighbouringTilesLeanOppositeWays() {
        for (i in 0..20) {
            assertTrue(tiltFor(i) * tiltFor(i + 1) < 0f)
        }
    }

    @Test
    fun tiltParityHoldsForNegativeIndices() {
        assertEquals(tiltFor(-2), tiltFor(0), 0f)
        assertEquals(tiltFor(-1), tiltFor(1), 0f)
    }

    @Test
    fun plateAngleMatchesTheMockupForTheFirstTiles() {
        assertEquals(140f, plateAngle(0), 0f)
        assertEquals(163f, plateAngle(1), 0f)
        assertEquals(186f, plateAngle(2), 0f)
    }

    @Test
    fun plateAngleStaysBoundedAndNeighboursDiffer() {
        for (i in -40..80) {
            val a = plateAngle(i)
            assertTrue(a >= 140f && a < 140f + 23f * 16)
            assertNotEquals(a, plateAngle(i + 1), 0f)
        }
    }

    @Test
    fun gradientAt90DegreesRunsLeftToRightThroughTheCentre() {
        val g = gradientLine(100f, 50f, 90f)
        assertEquals(0f, g.x0, 1e-3f)
        assertEquals(25f, g.y0, 1e-3f)
        assertEquals(100f, g.x1, 1e-3f)
        assertEquals(25f, g.y1, 1e-3f)
    }

    @Test
    fun gradientAt180DegreesRunsTopToBottom() {
        val g = gradientLine(100f, 50f, 180f)
        assertEquals(50f, g.x0, 1e-3f)
        assertEquals(0f, g.y0, 1e-3f)
        assertEquals(50f, g.x1, 1e-3f)
        assertEquals(50f, g.y1, 1e-3f)
    }

    @Test
    fun gradientAt135DegreesReachesTheCornersLikeCss() {
        // CSS: a 135deg gradient on a square runs from the top-left corner to the bottom-right corner.
        val g = gradientLine(80f, 80f, 135f)
        assertEquals(0f, g.x0, 1e-2f)
        assertEquals(0f, g.y0, 1e-2f)
        assertEquals(80f, g.x1, 1e-2f)
        assertEquals(80f, g.y1, 1e-2f)
    }

    @Test
    fun gradientIsSymmetricAboutTheCentre() {
        for (angle in listOf(0f, 33f, 140f, 163f, 250f, 359f)) {
            val g = gradientLine(160f, 112f, angle)
            assertEquals(80f, (g.x0 + g.x1) / 2f, 1e-3f)
            assertEquals(56f, (g.y0 + g.y1) / 2f, 1e-3f)
        }
    }
}

package com.andrewevmiller.tvlauncher.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The focus treatment's endpoints must match what the tiles looked like before the draw-phase rewrite. */
class FocusMathTest {
    private val line = Color(0xFF3A3A3A)
    private val accent = Color(0xFFB98FD6)

    @Test fun restIsUnscaledAndKeepsItsTilt() {
        assertEquals(1f, focusScale(0f, 1.06f), 0f)
        assertEquals(-2f, focusTilt(0f, -2f), 0f)
    }

    @Test fun focusedIsScaledAndStraight() {
        assertEquals(1.06f, focusScale(1f, 1.06f), 1e-6f)
        assertEquals(0f, focusTilt(1f, -2f), 0f)
    }

    @Test fun halfwayIsHalfway() {
        assertEquals(1.03f, focusScale(0.5f, 1.06f), 1e-6f)
        assertEquals(-1f, focusTilt(0.5f, -2f), 1e-6f)
    }

    @Test fun borderGrowsFromHairlineToThreeDp() {
        assertEquals(1.dp, borderWidth(0f, bleed = false))
        assertEquals(3.dp, borderWidth(1f, bleed = false))
        assertEquals(2.dp, borderWidth(0.5f, bleed = false))
    }

    @Test fun bleedBorderStartsHalfAsThick() {
        assertEquals(0.5.dp, borderWidth(0f, bleed = true))
        assertEquals(3.dp, borderWidth(1f, bleed = true))
    }

    @Test fun borderColorGoesFromLineToAccent() {
        assertEquals(line, borderColor(0f, bleed = false, line = line, accent = accent))
        assertEquals(accent, borderColor(1f, bleed = false, line = line, accent = accent))
    }

    @Test fun bleedBorderIsInvisibleAtRest() {
        assertEquals(0f, borderColor(0f, bleed = true, line = line, accent = accent).alpha, 0f)
        assertEquals(accent, borderColor(1f, bleed = true, line = line, accent = accent))
    }

    @Test fun strokeIsWholePixelsAndCappedAtHalfTheSmallerSide() {
        // 1dp, 1.4dp and 3dp at 2 px per dp
        assertEquals(2f, borderStrokePx(2f, 216f), 0f)
        assertEquals(3f, borderStrokePx(2.8f, 216f), 0f)
        assertEquals(6f, borderStrokePx(6f, 216f), 0f)
        // never more than half the smaller side, rounded up
        assertEquals(5f, borderStrokePx(60f, 9f), 0f)
    }
}

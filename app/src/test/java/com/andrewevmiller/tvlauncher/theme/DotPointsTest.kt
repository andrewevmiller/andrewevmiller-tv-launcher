package com.andrewevmiller.tvlauncher.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotPointsTest {
    @Test fun dotsSitHalfAStepIn() {
        val p = dotPoints(52f, Size(1920f, 1080f))
        assertEquals(Offset(26f, 26f), p.first())
        assertEquals(37 * 21, p.size) // centres 26, 78 ... 1898 across; 26 ... 1066 down
    }

    @Test fun aDotIsKeptOnlyIfItsCentreIsInside() {
        assertEquals(1, dotPoints(52f, Size(27f, 27f)).size)
        assertTrue(dotPoints(52f, Size(26f, 26f)).isEmpty())
    }
}

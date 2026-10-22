package com.andrewevmiller.tvlauncher.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.ceil

class ArtLimitsTest {
    /** The TV renders at 2 px per dp (CLAUDE.md, Target device). */
    private val pxPerDp = 2f

    @Test fun iconCoversTheTileIconAtFocusScaleButNotMuchMore() {
        val iconDp = 44f // HomeScreen's AppTile icon
        val focusScale = 1.06f
        val needed = ceil(iconDp * pxPerDp * focusScale).toInt()
        assertTrue("icon ${ArtLimits.ICON} px is smaller than the $needed px it is drawn at", ArtLimits.ICON >= needed)
        assertTrue("icon ${ArtLimits.ICON} px is more than 15% larger than needed", ArtLimits.ICON <= needed * 1.15f)
    }

    @Test fun hardwareBitmapsAreAllowedUpToTheCap() {
        assertTrue(ArtLimits.hardwareAllowed(0))
        assertTrue(ArtLimits.hardwareAllowed(ArtLimits.MAX_HARDWARE - 1))
        assertFalse(ArtLimits.hardwareAllowed(ArtLimits.MAX_HARDWARE))
        assertFalse(ArtLimits.hardwareAllowed(ArtLimits.MAX_HARDWARE + 1))
    }

    @Test fun gpuSlotsAreWhatIsLeftOfTheCap() {
        assertEquals(ArtLimits.MAX_HARDWARE, ArtLimits.gpuSlots(0))
        assertEquals(1, ArtLimits.gpuSlots(ArtLimits.MAX_HARDWARE - 1))
        assertEquals(0, ArtLimits.gpuSlots(ArtLimits.MAX_HARDWARE))
        assertEquals(0, ArtLimits.gpuSlots(ArtLimits.MAX_HARDWARE + 50))
    }

    @Test fun snapshotsFromBeforeTheArtworkResizeAreIgnored() {
        // A version 1 snapshot points at 192 px icon files; reading it would keep them. It must be rebuilt.
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(1); it.writeUTF("sig"); it.writeLong(1L); it.writeInt(0)
        }
        assertNull(SnapshotCodec.decode(out.toByteArray()))
        assertEquals(2, SnapshotCodec.VERSION)
    }
}

// purecheck:skip
package com.andrewevmiller.tvlauncher.home.data

import com.andrewevmiller.tvlauncher.theme.FieldDimens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one [ArtLimits] check that needs Compose's FieldDimens (a Dp). It lives apart from ArtLimitsTest so that file
 * stays pure and tools/purecheck can run it; this one runs under testDebugUnitTest only.
 */
class ArtLimitsTileSizeTest {
    /** The TV renders at 2 px per dp (CLAUDE.md, Target device). */
    private val pxPerDp = 2f

    @Test fun bannerIsRasterizedAtExactlyTheTileSize() {
        assertEquals(FieldDimens.bannerWidth.value * pxPerDp, ArtLimits.BANNER_W.toFloat(), 0f)
        assertEquals(FieldDimens.bannerHeight.value * pxPerDp, ArtLimits.BANNER_H.toFloat(), 0f)
    }
}

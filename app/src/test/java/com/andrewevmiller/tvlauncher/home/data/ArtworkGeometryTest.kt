package com.andrewevmiller.tvlauncher.home.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkGeometryTest {
    @Test
    fun exactBannerFillsTheBitmap() {
        assertEquals(FitRect(0, 0, 320, 180), fitRect(320, 180, 320, 180, cover = true))
    }

    @Test
    fun largerAndSmallerBannersScaleToFill() {
        assertEquals(FitRect(0, 0, 320, 180), fitRect(1280, 720, 320, 180, cover = true))
        assertEquals(FitRect(0, 0, 320, 180), fitRect(160, 90, 320, 180, cover = true))
    }

    @Test
    fun squareImageCoveringABannerIsCroppedVertically() {
        // 192x192 scaled to width 320 -> 320x320, centered vertically in 180 -> top -70.
        val r = fitRect(192, 192, 320, 180, cover = true)
        assertEquals(FitRect(0, -70, 320, 250), r)
        assertEquals(320, r.width)
        assertEquals(320, r.height)
    }

    @Test
    fun veryWideImageCoveringABannerIsCroppedHorizontally() {
        // 640x180 -> scale 1 -> width 640, centered in 320 -> left -160.
        assertEquals(FitRect(-160, 0, 480, 180), fitRect(640, 180, 320, 180, cover = true))
    }

    @Test
    fun squareIconFitsASquareBitmap() {
        assertEquals(FitRect(0, 0, 192, 192), fitRect(108, 108, 192, 192, cover = false))
        assertEquals(FitRect(0, 0, 192, 192), fitRect(512, 512, 192, 192, cover = false))
    }

    @Test
    fun wideIconIsLetterboxed() {
        // 200x100 into 192x192: scale 0.96 -> 192x96, centered -> top 48.
        assertEquals(FitRect(0, 48, 192, 144), fitRect(200, 100, 192, 192, cover = false))
    }

    @Test
    fun tallIconIsPillarboxed() {
        assertEquals(FitRect(48, 0, 144, 192), fitRect(100, 200, 192, 192, cover = false))
    }

    @Test
    fun unusableIntrinsicSizeFillsTheDestination() {
        assertEquals(FitRect(0, 0, 192, 192), fitRect(-1, -1, 192, 192, cover = false))
        assertEquals(FitRect(0, 0, 320, 180), fitRect(0, 50, 320, 180, cover = true))
        assertEquals(FitRect(0, 0, 320, 180), fitRect(50, 0, 320, 180, cover = true))
    }
}

package com.andrewevmiller.tvlauncher.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingArtTest {
    @Test fun smallArtIsNeverUpscaled() = assertEquals(300 to 300, NowPlayingArt.coverSize(300, 300))

    @Test fun artExactlyTheBoxIsLeftAlone() = assertEquals(600 to 216, NowPlayingArt.coverSize(600, 216))

    @Test fun bigSquareCoverArtShrinksUntilItsWidthFillsTheBox() {
        // 3000x3000 cropped into 600x216: the width is the limit, so 600x600
        assertEquals(600 to 600, NowPlayingArt.coverSize(3000, 3000))
    }

    @Test fun bigWideArtShrinksUntilItsHeightFillsTheBox() {
        // 4000x500: height 216/500 = 0.432 would make the width 1728; width limit 600/4000 = 0.15 is smaller than 0.432
        assertEquals(1728 to 216, NowPlayingArt.coverSize(4000, 500))
    }

    @Test fun resultAlwaysCoversTheBoxWhenTheSourceDid() {
        for ((w, h) in listOf(1000 to 1000, 1920 to 1080, 640 to 640, 5000 to 300, 700 to 4000)) {
            val (cw, ch) = NowPlayingArt.coverSize(w, h)
            assertTrue("$w x $h became $cw x $ch", cw >= NowPlayingArt.BOX_W && ch >= NowPlayingArt.BOX_H)
            assertTrue("$w x $h grew to $cw x $ch", cw <= w && ch <= h)
        }
    }

    @Test fun degenerateSizesPassThrough() = assertEquals(0 to 0, NowPlayingArt.coverSize(0, 0))
}

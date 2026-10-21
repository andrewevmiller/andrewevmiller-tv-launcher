package com.andrewevmiller.tvlauncher.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Layout constants. TV renders at a 960x540dp canvas regardless of panel resolution. */
object FieldDimens {
    /** 5% overscan-safe margins. */
    val safeHorizontal = 48.dp
    val safeVertical = 27.dp

    val rowGap = 28.dp
    val tileGap = 16.dp
    val hairline = 1.dp

    /** App banners are 16:9 (320x180 source). */
    val bannerWidth = 192.dp
    val bannerHeight = 108.dp

    val dotSpacing = 26.dp
    val dotRadius = 1.dp
}

object FieldShapes {
    val tile = RoundedCornerShape(6.dp)
    val chip = RoundedCornerShape(999.dp)
    val panel = RoundedCornerShape(0.dp)
}

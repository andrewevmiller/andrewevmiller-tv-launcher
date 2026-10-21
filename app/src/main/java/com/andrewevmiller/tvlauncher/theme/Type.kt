package com.andrewevmiller.tvlauncher.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.andrewevmiller.tvlauncher.R

/** Display: IBM Plex Serif 700. */
val PlexSerif = FontFamily(Font(R.font.plex_serif_bold, FontWeight.Bold))

/** Body: Geist (variable font; the weight axis is set from FontWeight). */
val Geist = FontFamily(
    Font(R.font.geist_variable, FontWeight.Normal),
    Font(R.font.geist_variable, FontWeight.Medium),
    Font(R.font.geist_variable, FontWeight.SemiBold),
)

/** Lab-notebook annotations: IBM Plex Mono. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

/**
 * Type scale for a 10-foot UI (960x540dp canvas). Body runs larger than the site's 17px;
 * nothing is justified, everything is left-anchored.
 */
@Immutable
data class FieldType(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

val FieldTypography = FieldType(
    display = TextStyle(fontFamily = PlexSerif, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.01).em),
    title = TextStyle(fontFamily = PlexSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.01).em),
    heading = TextStyle(fontFamily = PlexSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
    body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 30.sp),
    bodyStrong = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 30.sp),
    label = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.06.em),
    caption = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp),
)

val LocalFieldType = staticCompositionLocalOf { FieldTypography }

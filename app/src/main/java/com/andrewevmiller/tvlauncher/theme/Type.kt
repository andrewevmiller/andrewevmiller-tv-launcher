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
    // Named sizes the screens use, derived from the styles above (so no screen scales a style itself).
    val sectionLabel: TextStyle,
    val menuItem: TextStyle,
    val bandTitle: TextStyle,
    val bodySmall: TextStyle,
    val cta: TextStyle,
    val chip: TextStyle,
    val tileTitle: TextStyle,
    val tileCaption: TextStyle,
    val systemLabel: TextStyle,
    val recentTime: TextStyle,
    val recentName: TextStyle,
    val recentMeta: TextStyle,
    val pageTitle: TextStyle,
    val clock: TextStyle,
)

private val baseDisplay = TextStyle(fontFamily = PlexSerif, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.01).em)
private val baseTitle = TextStyle(fontFamily = PlexSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.01).em)
private val baseHeading = TextStyle(fontFamily = PlexSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em)
private val baseBody = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 30.sp)
private val baseBodyStrong = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 30.sp)
private val baseLabel = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.06.em)
private val baseCaption = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp)

val FieldTypography = FieldType(
    display = baseDisplay,
    title = baseTitle,
    heading = baseHeading,
    body = baseBody,
    bodyStrong = baseBodyStrong,
    label = baseLabel,
    caption = baseCaption,
    sectionLabel = baseLabel.copy(fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
    menuItem = baseBodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp),
    bandTitle = baseHeading.copy(fontSize = 24.sp, lineHeight = 28.sp),
    bodySmall = baseBody.copy(fontSize = 14.sp, lineHeight = 21.sp),
    cta = baseCaption.copy(fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.06.em),
    chip = baseCaption.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
    tileTitle = baseBodyStrong.copy(fontSize = 14.sp, lineHeight = 17.sp),
    tileCaption = baseCaption.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.06.em),
    systemLabel = baseBodyStrong.copy(fontSize = 13.sp, lineHeight = 16.sp),
    recentTime = baseCaption.copy(fontSize = 11.sp, lineHeight = 15.sp),
    recentName = baseBodyStrong.copy(fontSize = 13.5.sp, lineHeight = 17.sp),
    recentMeta = baseBody.copy(fontSize = 12.sp, lineHeight = 15.sp),
    pageTitle = baseTitle.copy(fontSize = 30.sp, lineHeight = 32.sp),
    clock = baseLabel.copy(fontSize = 13.sp),
)

val LocalFieldType = staticCompositionLocalOf { FieldTypography }

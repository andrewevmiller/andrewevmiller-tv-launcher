package com.andrewevmiller.tvlauncher.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color tokens from the andrewevmiller.com design language (§1).
 * One warm-neutral family plus exactly one accent. The accent is reserved for the
 * focused element and at most one other emphasis per screen.
 */
@Immutable
data class FieldColors(
    val bg: Color,
    val bgSoft: Color,
    val panel: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val isDark: Boolean,
)

val DarkFieldColors = FieldColors(
    bg = Color(0xFF171716),
    bgSoft = Color(0xFF1E1E1C),
    panel = Color(0xFF242422),
    line = Color(0xFF3A3A35),
    ink = Color(0xFFECEBE5),
    muted = Color(0xFFA3A29A),
    accent = Color(0xFFB98FD6),
    isDark = true,
)

val LightFieldColors = FieldColors(
    bg = Color(0xFFF2F1EE),
    bgSoft = Color(0xFFE9E8E2),
    panel = Color(0xFFE1E0D9),
    line = Color(0xFFCBC9C1),
    ink = Color(0xFF1C1C1A),
    muted = Color(0xFF5C5B54),
    accent = Color(0xFFB98FD6),
    isDark = false,
)

val LocalFieldColors = staticCompositionLocalOf { DarkFieldColors }

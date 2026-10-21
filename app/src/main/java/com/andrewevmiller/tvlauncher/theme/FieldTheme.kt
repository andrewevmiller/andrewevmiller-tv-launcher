package com.andrewevmiller.tvlauncher.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/** True when the user has asked for reduced motion; animations should snap or show a static frame. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Dark is the default mode. Light (parchment) is kept for the settings toggle. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FieldTheme(
    dark: Boolean = true,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkFieldColors else LightFieldColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.bg,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.bgSoft,
            onSurface = colors.ink,
            surfaceVariant = colors.panel,
            onSurfaceVariant = colors.muted,
            border = colors.line,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.bg,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.bgSoft,
            onSurface = colors.ink,
            surfaceVariant = colors.panel,
            onSurfaceVariant = colors.muted,
            border = colors.line,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalFieldColors provides colors,
            LocalFieldType provides FieldTypography,
            LocalReducedMotion provides reducedMotion,
            LocalContentColor provides colors.ink,
            content = content,
        )
    }
}

/** Accessors: `Field.colors.accent`, `Field.type.label`. */
object Field {
    val colors: FieldColors
        @Composable @ReadOnlyComposable get() = LocalFieldColors.current
    val type: FieldType
        @Composable @ReadOnlyComposable get() = LocalFieldType.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

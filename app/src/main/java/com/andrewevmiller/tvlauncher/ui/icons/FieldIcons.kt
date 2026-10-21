package com.andrewevmiller.tvlauncher.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.andrewevmiller.tvlauncher.home.data.AppCategory

/**
 * The line-icon set (24dp viewport, 1.7 stroke, round caps and joins), drawn from the mockup's SVG paths.
 *
 * CONTRACT: the names and types below are fixed so other modules can use them now. Task C5 replaces the
 * placeholder bodies with the real glyphs and must not rename or remove anything here.
 * Draw them with [FieldIcon], which tints them (`muted` at rest, `accent` only when focused).
 */
object FieldIcons {
    val Play: ImageVector by lazy { placeholder("Play") }
    val Note: ImageVector by lazy { placeholder("Note") }
    val Pad: ImageVector by lazy { placeholder("Pad") }
    val Tool: ImageVector by lazy { placeholder("Tool") }
    val Wave: ImageVector by lazy { placeholder("Wave") }
    val Port: ImageVector by lazy { placeholder("Port") }
    val Gear: ImageVector by lazy { placeholder("Gear") }
    val Wifi: ImageVector by lazy { placeholder("Wifi") }
    val Search: ImageVector by lazy { placeholder("Search") }
    val Folder: ImageVector by lazy { placeholder("Folder") }
    val Points: ImageVector by lazy { placeholder("Points") }

    /** Fallback glyph for an app with no banner and no usable icon. */
    fun forCategory(category: AppCategory): ImageVector = when (category) {
        AppCategory.VIDEO -> Play
        AppCategory.MUSIC -> Note
        AppCategory.GAMES -> Pad
        AppCategory.TOOLS -> Tool
        AppCategory.OTHER -> Wave
    }
}

private fun placeholder(name: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).build()

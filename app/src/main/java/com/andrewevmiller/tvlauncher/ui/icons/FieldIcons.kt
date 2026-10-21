package com.andrewevmiller.tvlauncher.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.andrewevmiller.tvlauncher.home.data.AppCategory

/**
 * The line-icon set (24dp viewport, 1.7 stroke, round caps and joins), drawn from the mockup's SVG paths.
 *
 * CONTRACT: the names and types below are fixed; add new glyphs, never rename or remove one.
 * Draw them with [FieldIcon], which tints them (`muted` at rest, `accent` only when focused).
 */
object FieldIcons {
    val Play: ImageVector by lazy { buildIcon("play") }
    val Note: ImageVector by lazy { buildIcon("note") }
    val Pad: ImageVector by lazy { buildIcon("pad") }
    val Tool: ImageVector by lazy { buildIcon("tool") }
    val Wave: ImageVector by lazy { buildIcon("wave") }
    val Port: ImageVector by lazy { buildIcon("port") }
    val Gear: ImageVector by lazy { buildIcon("gear") }
    val Wifi: ImageVector by lazy { buildIcon("wifi") }
    val Search: ImageVector by lazy { buildIcon("search") }
    val Folder: ImageVector by lazy { buildIcon("folder") }
    /** Four rounded squares: the "All apps" tile. */
    val Grid: ImageVector by lazy { buildIcon("grid") }
    val Points: ImageVector by lazy { buildIcon("points") }

    /** Fallback glyph for an app with no banner and no usable icon. */
    fun forCategory(category: AppCategory): ImageVector = when (category) {
        AppCategory.VIDEO -> Play
        AppCategory.MUSIC -> Note
        AppCategory.GAMES -> Pad
        AppCategory.TOOLS -> Tool
        AppCategory.OTHER -> Wave
    }
}

/**
 * Builds one glyph from [IconPaths]. The Color.Black below is a placeholder on purpose: [FieldIcon] tints the
 * whole vector with ColorFilter.tint, so only the shape matters. This is the one place in feature code where
 * a literal color is allowed; every visible color still comes from Field.colors at draw time.
 */
private fun buildIcon(name: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    for (primitive in IconPaths.all.getValue(name)) {
        val nodes = PathParser().parsePathString(primitive.d).toNodes()
        if (primitive.filled) {
            builder.addPath(pathData = nodes, fill = SolidColor(Color.Black))
        } else {
            builder.addPath(
                pathData = nodes,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    return builder.build()
}

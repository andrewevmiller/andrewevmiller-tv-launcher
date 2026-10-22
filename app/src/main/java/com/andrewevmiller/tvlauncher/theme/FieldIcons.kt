package com.andrewevmiller.tvlauncher.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line glyphs from the mockup: 24dp box, 1.7 stroke, round caps and joins. Tint at the call site. */
object FieldIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    private fun rect(x: Float, y: Float, s: Float) = "M$x,$y h$s v$s h-$s z"

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Pin = icon("pin", "M9 3h6l-1 6 3.5 4h-12L10 9z", "M12 13v8")
    val Bluetooth = icon("bluetooth", "M7 7l10 10-5 5V2l5 5L7 17")
    val Speaker = icon("speaker", "M4 9h4l5-4v14l-5-4H4z", "M16.5 9.5a3.5 3.5 0 0 1 0 5", "M19 7a7 7 0 0 1 0 10")
    val SpeakerMuted = icon("speaker-muted", "M4 9h4l5-4v14l-5-4H4z", "M16.5 9.5l5 5", "M21.5 9.5l-5 5")
    val Monitor = icon("monitor", "M3 5h18v11H3z", "M8 20h8", "M12 16v4")
    val Clock = icon("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Keyboard = icon("keyboard", "M3 7h18v10H3z", "M7 11h.01M11 11h.01M15 11h.01M7 14h10")
    val Access = icon("access", circle(12f, 5f, 1.8f), "M5 9h14", "M12 9v6", "M9 21l3-6 3 6")
    val Grid = icon("grid", rect(4f, 4f, 6.5f), rect(13.5f, 4f, 6.5f), rect(4f, 13.5f, 6.5f), rect(13.5f, 13.5f, 6.5f))
    val Port = icon("port", "M4 8h16v5l-2 3H6l-2-3z", "M8 11h8")
    val Gear = icon(
        "gear", circle(12f, 12f, 3f),
        "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1",
    )
    val Wifi = icon("wifi", "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0", circle(12f, 19f, 0.8f))
    val Search = icon("search", circle(10.5f, 10.5f, 6f), "m15 15 5.5 5.5")
}

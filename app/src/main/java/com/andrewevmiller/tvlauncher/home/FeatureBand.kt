package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile
import kotlinx.coroutines.android.awaitFrame
import kotlin.random.Random

/** What the band shows: an app and a line about when it was last opened (null if never). */
data class Feature(val app: LauncherApp, val lastOpened: String?)

/**
 * The feature band: a panel with hairlines above and below running edge to edge, a kicker, a
 * serif title, one line of body, a CTA, and the dot-field with slow ripples on the right.
 */
@Composable
fun FeatureBand(feature: Feature, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Field.colors
    Column(
        modifier
            .layout { measurable, c ->
                // Break out of the 48dp safe padding so the panel meets both screen edges.
                val bleed = FieldDimens.safeHorizontal.roundToPx() * 2
                val p = measurable.measure(c.copy(minWidth = c.maxWidth + bleed, maxWidth = c.maxWidth + bleed))
                layout(c.maxWidth, p.height) { p.place(-bleed / 2, 0) }
            }
            .background(colors.panel),
    ) {
        Box(Modifier.fillMaxWidth().height(FieldDimens.hairline).background(colors.line))
        Row(
            Modifier.padding(horizontal = FieldDimens.safeHorizontal, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "FEATURED · ${feature.app.label}".uppercase(),
                    style = Field.type.label.copy(fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
                    color = colors.muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    feature.app.label,
                    style = Field.type.heading.copy(fontSize = 24.sp, lineHeight = 28.sp),
                    color = colors.ink, maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    feature.lastOpened?.let { "Last opened $it. Select to open it again." } ?: "Nothing opened yet. Select to launch it.",
                    style = Field.type.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
                    color = colors.muted,
                    modifier = Modifier.width(320.dp),
                )
                Spacer(Modifier.height(12.dp))
                OpenButton(onOpen)
            }
            Spacer(Modifier.width(28.dp))
            DotField(Modifier.size(300.dp, 108.dp))
        }
        Box(Modifier.fillMaxWidth().height(FieldDimens.hairline).background(colors.line))
    }
}

@Composable
private fun OpenButton(onClick: () -> Unit) {
    val colors = Field.colors
    var focused by remember { mutableStateOf(false) }
    Text(
        "Open →",
        style = Field.type.caption.copy(fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.06.em),
        // The one place the accent fills: the focused CTA, with dark text on it.
        color = if (focused) colors.bg else colors.ink,
        modifier = Modifier
            .onFocusChanged { focused = it.hasFocus }
            .fieldTile(onClick)
            .background(if (focused) colors.accent else Color.Transparent, FieldShapes.tile)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private const val DOT_STEP = 18f
private const val DOT_FRAME_NS = 50_000_000L // the ripples are slow: 20 frames a second looks the same and costs a third

/**
 * Dots on an 18-unit grid (in a 600x216 space) lit by ripples that spread and fade; still when motion is reduced.
 *
 * The clock is read only while drawing, so the animation never recomposes anything. Unlit dots are one
 * cached point batch; only dots a ripple is clearly touching get their own circle, with a
 * color from a precomputed ramp.
 */
@Composable
private fun DotField(modifier: Modifier) {
    val colors = Field.colors
    val reduced = Field.reducedMotion
    var now by remember { mutableLongStateOf(0L) }
    val ripples = remember { mutableListOf(Ripple(380f, 100f, -1.2f, 120f, 26f, 5f)) }
    LaunchedEffect(reduced) {
        if (reduced) { now = 1; return@LaunchedEffect }
        val start = System.nanoTime()
        var last = -DOT_FRAME_NS
        while (true) {
            awaitFrame()
            val elapsed = System.nanoTime() - start
            if (elapsed - last < DOT_FRAME_NS) continue
            last = elapsed
            val t = elapsed / 1e9f
            ripples.removeAll { t - it.t0 > it.life }
            if (ripples.size < 2 && (ripples.isEmpty() || t - ripples.last().t0 > 1.8f)) {
                ripples += Ripple(60 + Random.nextFloat() * 480, 30 + Random.nextFloat() * 160, t, 110f, 24f, 5f)
            }
            now = elapsed
        }
    }
    val ramp = remember(colors.line, colors.muted, colors.accent) {
        Array(RAMP) { i ->
            val e = i / (RAMP - 1f)
            if (e < 0.5f) lerp(colors.line, colors.muted, e * 2) else lerp(colors.muted, colors.accent, (e - 0.5f) * 2)
        }
    }
    val cache = remember { arrayOfNulls<Pair<Float, List<Offset>>>(1) }
    Canvas(modifier.background(colors.bg).border(FieldDimens.hairline, colors.line, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))) {
        val t = if (reduced) 0.2f else now / 1e9f // the only read of the clock: draw phase
        val k = size.width / 600f
        val all = cache[0]?.takeIf { it.first == k }?.second ?: buildList {
            var y = DOT_STEP / 2
            while (y < 216f) {
                var x = DOT_STEP / 2
                while (x < 600f) { add(Offset(x * k, y * k)); x += DOT_STEP }
                y += DOT_STEP
            }
        }.also { cache[0] = k to it }
        drawPoints(all, PointMode.Points, ramp[0], strokeWidth = 3.2f * k, cap = StrokeCap.Round)
        var y = DOT_STEP / 2
        while (y < 216f) {
            var x = DOT_STEP / 2
            while (x < 600f) {
                val e = dotEnergy(x, y, t, ripples)
                if (e > LIT) drawCircle(ramp[(e * (RAMP - 1)).toInt()], 1.6f * (1 + e * 1.3f) * k, Offset(x * k, y * k))
                x += DOT_STEP
            }
            y += DOT_STEP
        }
    }
}

private const val RAMP = 65

/** Below this a dot is indistinguishable from an unlit one (under 4% bigger, color within 6% of the base). */
private const val LIT = 0.03f

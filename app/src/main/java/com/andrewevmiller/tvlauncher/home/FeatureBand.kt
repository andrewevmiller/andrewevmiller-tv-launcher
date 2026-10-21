package com.andrewevmiller.tvlauncher.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.home.data.NowPlaying
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

/** An app the band can feature, with a line about when it was last opened. */
data class Feature(val app: LauncherApp, val lastOpened: String?)

private const val ROTATE_MS = 6000L

/**
 * The feature band: a panel with hairlines above and below running edge to edge, a kicker, a
 * serif title, one line of body, buttons, and a picture panel on the right.
 *
 * What it shows: whatever is playing right now if something is, otherwise your recent apps one at a
 * time. The recents turn over every six seconds (not at all with reduced motion), with a row of dots
 * saying where you are. The Open button opens whichever one is showing when you press it.
 */
@Composable
fun FeatureBand(
    features: List<Feature>,
    nowPlaying: NowPlaying?,
    onOpen: (Feature) -> Unit,
    onOpenPlaying: (NowPlaying) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val reduced = Field.reducedMotion
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(features.size, reduced, nowPlaying == null) {
        if (features.size < 2 || reduced || nowPlaying != null) return@LaunchedEffect
        while (true) {
            delay(ROTATE_MS)
            index = (index + 1) % features.size
        }
    }
    if (index >= features.size) index = 0
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
            // A fixed height keeps the band from changing size as the text changes.
            Box(Modifier.weight(1f).height(152.dp)) {
                if (nowPlaying != null) {
                    PlayingText(nowPlaying, onOpenPlaying)
                } else {
                    val shown = features[index]
                    Crossfade(
                        targetState = shown,
                        animationSpec = if (reduced) snap() else tween(350),
                        label = "featureText",
                    ) { f -> RecentText(f, features.size, index, onOpen) }
                }
            }
            Spacer(Modifier.width(28.dp))
            Box(Modifier.size(300.dp, 108.dp)) {
                val art = nowPlaying?.art
                if (art != null) {
                    Image(
                        art, null,
                        Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(4.dp))
                            .border(FieldDimens.hairline, colors.line, RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    DotField(Modifier.size(300.dp, 108.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(FieldDimens.hairline).background(colors.line))
    }
}

@Composable
private fun RecentText(feature: Feature, count: Int, index: Int, onOpen: (Feature) -> Unit) {
    val colors = Field.colors
    Column(Modifier.fillMaxWidth()) {
        Kicker("FEATURED · ${feature.app.label}")
        Spacer(Modifier.height(4.dp))
        Title(feature.app.label)
        Spacer(Modifier.height(4.dp))
        Body(feature.lastOpened?.let { "Last opened $it. Select to open it again." } ?: "Select to open it.")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BandButton("Open →") { onOpen(feature) }
            if (count > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(count) { i ->
                        Box(
                            Modifier.size(6.dp).background(if (i == index) colors.ink else colors.line, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayingText(playing: NowPlaying, onOpen: (NowPlaying) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Kicker("NOW PLAYING · ${playing.appLabel}")
        Spacer(Modifier.height(4.dp))
        Title(playing.title)
        Spacer(Modifier.height(4.dp))
        Body(playing.subtitle ?: if (playing.playing) "Playing" else "Paused")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BandButton(if (playing.playing) "Pause" else "Play") { playing.onToggle() }
            BandButton("Open →") { onOpen(playing) }
        }
    }
}

@Composable
private fun Kicker(text: String) {
    Text(
        text.uppercase(),
        style = Field.type.label.copy(fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
        color = Field.colors.muted, maxLines = 1,
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = Field.type.heading.copy(fontSize = 24.sp, lineHeight = 28.sp),
        color = Field.colors.ink, maxLines = 1,
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = Field.type.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
        color = Field.colors.muted, maxLines = 2,
        modifier = Modifier.width(320.dp),
    )
}

@Composable
private fun BandButton(label: String, onClick: () -> Unit) {
    val colors = Field.colors
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        style = Field.type.caption.copy(fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.06.em),
        // The one place the accent fills: the focused button, with dark text on it.
        color = if (focused) colors.bg else colors.ink,
        modifier = Modifier
            .onFocusChanged { focused = it.hasFocus }
            .fieldTile(onClick)
            .background(if (focused) colors.accent else Color.Transparent, FieldShapes.tile)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private class Ripple(val x: Float, val y: Float, val t0: Float, val speed: Float, val width: Float, val life: Float)

/** Dots on an 18-unit grid (in a 600x216 space) lit by ripples that spread and fade; still when motion is reduced. */
@Composable
private fun DotField(modifier: Modifier) {
    val colors = Field.colors
    val reduced = Field.reducedMotion
    var now by remember { mutableLongStateOf(0L) }
    val ripples = remember { mutableListOf(Ripple(380f, 100f, -1.2f, 120f, 26f, 5f)) }
    LaunchedEffect(reduced) {
        if (reduced) { now = 1; return@LaunchedEffect }
        val start = System.nanoTime()
        while (true) {
            awaitFrame()
            now = System.nanoTime() - start
        }
    }
    val t = if (reduced) 0.2f else now / 1e9f
    ripples.removeAll { t - it.t0 > it.life }
    if (!reduced && ripples.size < 2 && (ripples.isEmpty() || t - ripples.last().t0 > 1.8f)) {
        ripples += Ripple(60 + Random.nextFloat() * 480, 30 + Random.nextFloat() * 160, t, 110f, 24f, 5f)
    }
    Canvas(modifier.background(colors.bg).border(FieldDimens.hairline, colors.line, RoundedCornerShape(4.dp))) {
        val k = size.width / 600f
        val step = 18f
        var y = step / 2
        while (y < 216f) {
            var x = step / 2
            while (x < 600f) {
                var e = 0f
                for (r in ripples) {
                    val age = t - r.t0
                    val d = hypot(x - r.x, y - r.y)
                    val diff = d - age * r.speed
                    e += exp(-(diff * diff) / (2 * r.width * r.width)) * maxOf(0f, 1 - age / r.life)
                }
                e = minOf(1f, e)
                val c = if (e < 0.5f) lerp(colors.line, colors.muted, e * 2) else lerp(colors.muted, colors.accent, (e - 0.5f) * 2)
                drawCircle(c, 1.6f * (1 + e * 1.3f) * k, Offset(x * k, y * k))
                x += step
            }
            y += step
        }
    }
}

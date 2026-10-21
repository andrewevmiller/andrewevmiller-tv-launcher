package com.andrewevmiller.tvlauncher.ui.components

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile

/** The canvas slot on the right of the band: 300x108dp, like the mockup's `.band-viz`. */
private val BandCanvasWidth = 300.dp
private val BandCanvasHeight = 108.dp

/** Inner left/right inset when the band does not bleed to the screen edges. */
private val BandInset = 20.dp

/**
 * The feature band (the mockup's `.band`): a `panel` strip between two hairlines holding a mono kicker
 * (muted, upper-cased), a Plex Serif title, a Geist body, and a call-to-action. The CTA is outlined at
 * rest and fills with the accent while focused, the one place a fill is allowed because it is the focused
 * element; its label then switches to the dark ink for contrast. A small [canvas] sits on the right in a
 * hairline frame (default: [BandCanvas], the dot-field ripple).
 *
 * Width: the band is as wide as the space it is given. If the slot sits inside the home screen's safe-area
 * padding, pass `bleed = FieldDimens.safeHorizontal` to run the `panel` out to the screen edges like the
 * mockup; the text and canvas stay on the safe margin either way.
 *
 * OK on the CTA plays `FieldSound.select()` unless [playSelect] is false (pass false when [onCta] plays the
 * launched app's motif). [ctaModifier] goes on the focusable CTA, e.g. a `focusRequester`. LEFT/RIGHT on
 * the lone CTA play `FieldSound.edge()`.
 *
 * [canvas] is the last parameter so it can be passed as a trailing lambda.
 */
@Composable
fun FeatureBand(
    kicker: String,
    title: String,
    body: String,
    ctaLabel: String,
    onCta: () -> Unit,
    modifier: Modifier = Modifier,
    ctaModifier: Modifier = Modifier,
    bleed: Dp = 0.dp,
    playSelect: Boolean = true,
    canvas: @Composable () -> Unit = { BandCanvas() },
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    val inset = if (bleed > 0.dp) bleed else BandInset
    Column(
        modifier
            .layoutBleed(horizontal = bleed, vertical = 0.dp)
            .fillMaxWidth()
            .background(colors.panel),
    ) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = inset, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = kicker.uppercase(),
                    style = Field.type.label,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = title,
                    style = Field.type.heading,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = Field.type.body,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                BandCta(
                    label = ctaLabel,
                    onClick = {
                        if (playSelect) sound.select()
                        onCta()
                    },
                    modifier = ctaModifier.rowEdgeKeys(0, 1) { sound.edge() },
                )
            }
            Box(
                Modifier
                    .size(BandCanvasWidth, BandCanvasHeight)
                    .clip(FieldShapes.tile)
                    .background(colors.bg)
                    .border(FieldDimens.hairline, colors.line, FieldShapes.tile),
            ) {
                canvas()
            }
        }
        Hairline()
    }
}

/** Outlined at rest (the standard hairline), filled with the accent while focused. */
@Composable
private fun BandCta(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Field.colors
    var focused by remember { mutableStateOf(false) }
    // Dark ink on the lilac fill in both modes (`bg` is dark in dark mode, `ink` is dark in light mode).
    val onAccent = if (colors.isDark) colors.bg else colors.ink
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .fieldTile(onClick = onClick)
            .background(if (focused) colors.accent else Color.Transparent, FieldShapes.tile)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = Field.type.label,
            color = if (focused) onAccent else colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(FieldDimens.hairline)
            .background(Field.colors.line),
    )
}

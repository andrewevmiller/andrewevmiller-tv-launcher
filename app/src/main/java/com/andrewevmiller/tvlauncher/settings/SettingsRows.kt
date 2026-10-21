package com.andrewevmiller.tvlauncher.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldFocus
import com.andrewevmiller.tvlauncher.theme.fieldTile

/*
 * Building blocks for SettingsScreen. All focus goes through Modifier.fieldTile()/fieldFocus(), colors and
 * type come from Field.*, and the accent is used only for the focused element and for the selected-waveform
 * marker (the screen's single other emphasis).
 */

/**
 * Rows sit this far inside the safe-area edge so the focused row's border and scale-up still end inside it.
 * A full-width row scales by [WideRowScale], growing about 8dp per side at most.
 */
internal val RowInset = 8.dp

/** Full-width rows scale up less than tiles do, so the focus ring never leaves the safe area. */
internal const val WideRowScale = 1.02f

// ---------------------------------------------------------------------------------------------
// Static pieces
// ---------------------------------------------------------------------------------------------

/** A hairline rule with a mono uppercase label, like the home screen's row frames. */
@Composable
internal fun SectionHeader(label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(FieldDimens.hairline)
                .background(Field.colors.line),
        )
        Spacer(Modifier.height(12.dp))
        Text(label.uppercase(), style = Field.type.label, color = Field.colors.muted)
    }
}

/** Muted mono annotation under a row. Not focusable. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = RowInset + 16.dp),
        style = Field.type.caption,
        color = Field.colors.muted,
    )
}

/** Mono label naming a group of rows (for example the waveform picker). Not focusable. */
@Composable
internal fun SubLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(horizontal = RowInset + 16.dp),
        style = Field.type.label,
        color = Field.colors.muted,
    )
}

// ---------------------------------------------------------------------------------------------
// The row shell: a bordered, focusable tile that fills the width
// ---------------------------------------------------------------------------------------------

/**
 * One focusable row. [onClick] null makes a read-only row that still takes focus (so the D-pad can scroll
 * to it). [keyHandler] sees keys while the row has focus, for left/right adjustment.
 */
@Composable
private fun RowShell(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    inset: Dp = RowInset,
    keyHandler: ((KeyEvent) -> Boolean)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val keys: Modifier = if (keyHandler != null) Modifier.onKeyEvent(keyHandler) else Modifier
    val focus: Modifier = if (onClick != null) {
        Modifier.fieldTile(onClick = onClick, focusedScale = WideRowScale)
    } else {
        Modifier.fieldFocus(focusedScale = WideRowScale).focusable()
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .then(keys)
            .then(focus)
            .background(Field.colors.bgSoft, FieldShapes.tile)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

/** Label on the left, `on` / `off` in mono on the right. */
@Composable
internal fun ToggleRow(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    RowShell(
        onClick = onToggle,
        modifier = modifier.semantics {
            role = Role.Switch
            stateDescription = if (on) "On" else "Off"
        },
    ) {
        Text(label, modifier = Modifier.weight(1f), style = Field.type.body, color = colors.ink)
        Text(if (on) "on" else "off", style = Field.type.label, color = if (on) colors.ink else colors.muted)
    }
}

/**
 * A radio option: a ring (filled when selected), the label, and an optional mono detail.
 * [accentMarker] paints the selected marker in the accent; only the waveform picker sets it, because the
 * selected waveform is this screen's one accent emphasis besides focus.
 */
@Composable
internal fun OptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accentMarker: Boolean = false,
    inset: Dp = RowInset,
) {
    val colors = Field.colors
    RowShell(
        onClick = onSelect,
        modifier = modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        inset = inset,
    ) {
        RadioMark(selected = selected, accent = accentMarker)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = Field.type.body, color = colors.ink, maxLines = 1)
        if (detail != null) {
            Spacer(Modifier.width(12.dp))
            Text(detail, style = Field.type.caption, color = colors.muted, maxLines = 1)
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean, accent: Boolean) {
    val colors = Field.colors
    val ring = when {
        !selected -> colors.muted
        accent -> colors.accent
        else -> colors.ink
    }
    Canvas(Modifier.size(16.dp)) {
        drawCircle(color = ring, radius = 6.dp.toPx(), style = Stroke(width = 1.6.dp.toPx()))
        if (selected) drawCircle(color = ring, radius = 3.2.dp.toPx())
    }
}

/** A row that does something when OK is pressed (Start now). */
@Composable
internal fun ActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RowShell(onClick = onClick, modifier = modifier) {
        Text(label, modifier = Modifier.weight(1f), style = Field.type.body, color = Field.colors.ink)
    }
}

/**
 * The dot-grid brightness stepper. Left / right move one tenth (the value is shown in mono, with a
 * notched hairline scale); OK steps up and wraps. At an end the key plays the edge sound instead.
 */
@Composable
internal fun StepperRow(
    label: String,
    alpha: Float,
    onStep: (deltaTenths: Int) -> Unit,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    RowShell(
        onClick = onCycle,
        modifier = modifier,
        keyHandler = { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                val delta = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> 0
                }
                if (delta == 0) {
                    false
                } else {
                    if (DotBrightness.canStep(alpha, delta)) onStep(delta) else sound.edge()
                    true
                }
            }
        },
    ) {
        Text(label, modifier = Modifier.weight(1f), style = Field.type.body, color = colors.ink)
        NotchMeter(tenths = DotBrightness.toTenths(alpha))
        Spacer(Modifier.width(14.dp))
        Text(
            DotBrightness.label(alpha),
            modifier = Modifier.width(36.dp),
            style = Field.type.label,
            color = colors.ink,
            maxLines = 1,
        )
    }
}

/** An 11-notch hairline scale with a tick at the current tenth. No fill, no color. */
@Composable
private fun NotchMeter(tenths: Int, modifier: Modifier = Modifier) {
    val colors = Field.colors
    val line = colors.line
    val notch = colors.muted
    val marker = colors.ink
    Canvas(modifier.size(width = 132.dp, height = 18.dp)) {
        val inset = 2.dp.toPx()
        val span = size.width - 2f * inset
        val midY = size.height / 2f
        val hair = 1.dp.toPx()
        drawLine(line, Offset(inset, midY), Offset(inset + span, midY), strokeWidth = hair)
        for (i in 0..10) {
            val x = inset + span * i / 10f
            drawLine(notch, Offset(x, midY - 3.dp.toPx()), Offset(x, midY + 3.dp.toPx()), strokeWidth = hair)
        }
        val mx = inset + span * tenths / 10f
        drawLine(
            marker,
            Offset(mx, 1.dp.toPx()),
            Offset(mx, size.height - 1.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** A read-only block of mono lines that still takes focus, so the D-pad scrolls to it (About). */
@Composable
internal fun InfoBlock(lines: List<String>, modifier: Modifier = Modifier) {
    val colors = Field.colors
    RowShell(onClick = null, modifier = modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEachIndexed { index, line ->
                Text(line, style = Field.type.caption, color = if (index == 0) colors.ink else colors.muted)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Per-app motif row
// ---------------------------------------------------------------------------------------------

private val RerollWidth = 104.dp
private val MuteWidth = 104.dp
private val ResetWidth = 88.dp

/**
 * App name and package on the left; Re-roll, Mute / Unmute and Reset on the right. The three pill slots keep
 * fixed widths (Reset leaves an empty slot when there is nothing to undo) so up/down on the D-pad stays in
 * the same column from row to row.
 */
@Composable
internal fun MotifListRow(
    row: MotifRow,
    onReroll: () -> Unit,
    onToggleMute: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val rerollFocus = remember { FocusRequester() }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = RowInset, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                row.label,
                style = Field.type.body,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    row.packageName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = Field.type.caption,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = row.statusLabel
                if (status != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(status, style = Field.type.caption, color = colors.ink, maxLines = 1)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                label = "Re-roll",
                onClick = onReroll,
                modifier = Modifier.width(RerollWidth).focusRequester(rerollFocus),
            )
            PillButton(
                label = if (row.override.muted) "Unmute" else "Mute",
                onClick = onToggleMute,
                modifier = Modifier.width(MuteWidth),
            )
            if (row.isOverridden) {
                PillButton(
                    label = "Reset",
                    onClick = {
                        // This pill disappears once the override is cleared; hand focus to its neighbour first.
                        rerollFocus.requestFocus()
                        onReset()
                    },
                    modifier = Modifier.width(ResetWidth),
                )
            } else {
                Spacer(Modifier.width(ResetWidth))
            }
        }
    }
}

/** A small left-aligned mono button with the standard tile focus. */
@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(36.dp)
            .fieldTile(onClick = onClick)
            .background(Field.colors.bgSoft, FieldShapes.tile)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = Field.type.label, color = Field.colors.ink, maxLines = 1)
    }
}

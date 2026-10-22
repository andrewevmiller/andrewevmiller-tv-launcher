package com.andrewevmiller.tvlauncher.home.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile

/** One action chip in the strip under the grid (Unpin, Hide, Pin). */
internal class StripAction(val label: String, val onActivate: () -> Unit)

/**
 * The hairline-ruled strip under the grid with the actions for the lifted (Home) or long-pressed (drawer) app:
 * `fieldTile` chips, then a mono hint. Left and right move between the chips, up calls [onUp], down is an edge.
 * [requesters] needs one entry per action.
 */
@Composable
internal fun ActionStrip(
    actions: List<StripAction>,
    hint: String,
    requesters: List<FocusRequester>,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(FieldDimens.hairline)
                .background(colors.line),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            // Room for the chips' focus scale-up.
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { i, action ->
                StripChip(
                    label = action.label,
                    onClick = action.onActivate,
                    modifier = Modifier
                        .focusRequester(requesters[i])
                        .onPreviewKeyEvent { event -> stripKey(event, i, actions.size, requesters, onUp, sound) },
                )
            }
            Text(text = hint, style = Field.type.caption, color = colors.muted)
        }
    }
}

private fun stripKey(
    event: KeyEvent,
    index: Int,
    count: Int,
    requesters: List<FocusRequester>,
    onUp: () -> Unit,
    sound: FieldSound,
): Boolean {
    val direction = event.gridDirection() ?: return false
    if (event.type == KeyEventType.KeyDown) {
        // The chips are one grid row of `count` columns, so the same rules apply.
        when (val move = gridMove(index, count, count, direction)) {
            is GridMove.Stay -> requesters[move.index].tryRequest()
            GridMove.ExitUp -> onUp()
            GridMove.Edge, GridMove.ExitDown -> if (event.nativeKeyEvent.repeatCount == 0) sound.edge()
        }
    }
    return true
}

@Composable
private fun StripChip(label: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = Field.colors
    Box(
        modifier
            .fieldTile(onClick = onClick, shape = FieldShapes.chip)
            .background(colors.bgSoft, FieldShapes.chip)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(text = label.uppercase(), style = Field.type.label, color = colors.ink)
    }
}

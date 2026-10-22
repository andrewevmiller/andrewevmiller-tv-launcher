package com.andrewevmiller.tvlauncher.marginnote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile

/** Plain holder: changing it never recomposes. */
private class EditFlag(var on: Boolean = false)

/**
 * The margin note line on Home: `NOTE  <text>` in the mono style, with a hairline under it. It is a focus stop:
 * OK calls [onOpen] (Home then sets [editing]). While [editing] the row is replaced by [MarginNoteEditor].
 *
 * An empty note takes no space: the row keeps its focus stop, but the text, the hairline and the gap are only
 * drawn while the row has focus, when it reads `NOTE  add a note`. When editing ends, focus returns to the row.
 */
@Composable
fun MarginNoteRow(
    note: String,
    editing: Boolean,
    onOpen: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val wasEditing = remember { EditFlag() }

    // Back from the editor (or Done) returns focus to this row, once the row is composed again.
    LaunchedEffect(editing) {
        val was = wasEditing.on
        wasEditing.on = editing
        if (was && !editing) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }

    val hidden = note.isEmpty() && !focused && !editing
    Column(modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }) {
        if (editing) {
            MarginNoteEditor(initial = note, onSave = onSave)
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .then(if (hidden) Modifier.height(FieldDimens.hairline) else Modifier)
                    // bleed: no resting frame, as in the mockup; the accent frame appears only on focus.
                    .fieldTile(onOpen, FieldShapes.chip, focusedScale = 1.02f, bleed = true)
                    .then(if (hidden) Modifier else Modifier.padding(horizontal = 8.dp, vertical = 2.dp)),
            ) {
                if (!hidden) {
                    Text(
                        MarginNoteRules.rowText(note),
                        style = Field.type.cta,
                        color = if (focused && note.isNotEmpty()) colors.ink else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!hidden) {
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(FieldDimens.hairline).background(colors.line))
            Spacer(Modifier.height(12.dp))
        }
    }
}

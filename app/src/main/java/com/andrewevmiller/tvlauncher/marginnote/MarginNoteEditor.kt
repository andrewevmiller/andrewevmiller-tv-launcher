package com.andrewevmiller.tvlauncher.marginnote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldFocus

/**
 * The margin note's one-line editor, drawn in place of the note row while it is open. The text field takes
 * focus and the on-screen keyboard is shown with it. Done (the IME action) calls [onSave] with the cleaned
 * text. Back is not handled here: the Home screen's Back handler discards and returns focus to the row.
 *
 * The arrow keys stay in the field: Left and Right move the caret, and Up and Down are swallowed, so focus
 * cannot leave the editor by the remote while it is open.
 */
@Composable
fun MarginNoteEditor(
    initial: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val latestSave by rememberUpdatedState(onSave)
    val latestValue by rememberUpdatedState(value)

    // Focus and the keyboard together, after the frame that put the field on screen.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        keyboard?.show()
    }

    Row(
        modifier
            .fillMaxWidth()
            .fieldFocus(FieldShapes.chip, focusedScale = 1f)
            .background(colors.bgSoft, FieldShapes.chip)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(MarginNoteRules.LABEL + "  ", style = Field.type.cta, color = colors.muted)
        BasicTextField(
            value = value,
            onValueChange = { typed ->
                val limited = MarginNoteRules.limitWhileEditing(typed.text)
                value = if (limited == typed.text) {
                    typed
                } else {
                    TextFieldValue(limited, TextRange(limited.length))
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(requester)
                .onKeyEvent { event ->
                    // The field has already used Left and Right to move the caret; whatever it did not
                    // use stops here, so the Home screen's focus movement never sees an arrow from the editor.
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                },
            textStyle = Field.type.cta.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboard?.hide()
                    latestSave(latestValue.text)
                },
            ),
        )
        Text("Done saves · Back discards", style = Field.type.cta, color = colors.muted)
    }
}

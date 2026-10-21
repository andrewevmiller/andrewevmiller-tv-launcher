package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldFocus
import kotlinx.coroutines.android.awaitFrame

enum class MenuAction(val label: String) {
    MOVE("Move"),
    PIN("Pin to Home"),
    UNPIN("Unpin from Home"),
}

/**
 * The long-press menu for one app: a small panel over a dimmed screen. Up and Down choose, OK picks,
 * Back (handled by the activity) closes. Focus is kept inside: Left, Right and the edges do nothing.
 */
@Composable
fun PinMenu(app: LauncherApp, actions: List<MenuAction>, onPick: (MenuAction) -> Unit) {
    val colors = Field.colors
    val requesters = remember(actions) { actions.map { FocusRequester() } }
    var selected by remember(actions) { mutableStateOf(0) }
    // The OK that opened this menu may still be held, and its repeats must not pick anything.
    var freshPress by remember { mutableStateOf(false) }
    LaunchedEffect(actions) {
        awaitFrame()
        runCatching { requesters[0].requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.72f))
            .focusable(false),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(260.dp)
                .background(colors.panel, FieldShapes.tile)
                .border(1.dp, colors.line, FieldShapes.tile)
                .padding(16.dp),
        ) {
            Text(
                app.label.uppercase(),
                style = Field.type.label.copy(fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
                color = colors.muted, maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))
            actions.forEachIndexed { i, action ->
                Text(
                    action.label,
                    style = Field.type.bodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = colors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .focusRequester(requesters[i])
                        .onPreviewKeyEvent { e ->
                            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                            val down = e.type == KeyEventType.KeyDown
                            when {
                                ok && down -> {
                                    if (e.nativeKeyEvent.repeatCount == 0) freshPress = true
                                    true
                                }
                                ok -> {
                                    if (freshPress) {
                                        freshPress = false
                                        onPick(action)
                                    }
                                    true
                                }
                                !down -> false
                                e.key == Key.DirectionDown -> {
                                    if (selected < actions.lastIndex) selected++
                                    requesters[selected].requestFocus()
                                    true
                                }
                                e.key == Key.DirectionUp -> {
                                    if (selected > 0) selected--
                                    requesters[selected].requestFocus()
                                    true
                                }
                                e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> true
                                else -> false
                            }
                        }
                        .fieldFocus(FieldShapes.tile, focusedScale = 1.03f)
                        .focusable()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

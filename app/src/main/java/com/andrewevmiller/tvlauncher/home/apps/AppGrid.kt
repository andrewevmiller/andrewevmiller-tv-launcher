package com.andrewevmiller.tvlauncher.home.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.sound.LocalSoundRow
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import kotlinx.coroutines.delay

/** How long a dropped tile stays where the user put it while the repository catches up. */
private const val SettleMillis = 800L

/**
 * The Home "Apps" section (BUILD_PLAN section 5.4): [apps] as a wrapping [FieldDimens.appGridColumns]-column grid,
 * left aligned, ending in an "All apps" cell that calls [onOpenDrawer]. Non-lazy (a Column of Rows) so it can sit
 * in one item of HomeScreen's LazyColumn; pinned lists are short.
 *
 * **D-pad.** Handled per tile with [gridMove]: left and right stay in the grid row and never wrap
 * (`FieldSound.edge()` at a row end), up and down keep the column in the adjacent row (a shorter row lands on
 * its last tile). Up from the first row calls [onExitUp], down from the last row [onExitDown]; when those are
 * null the focus manager moves focus up or down. The focus tick is pitched per grid row: every row provides
 * `LocalSoundRow` as the current value plus the row index.
 *
 * **Focus survival.** When [apps] changes and the grid had focus, focus is put back on the cell now at the
 * previous index, clamped to the new size. It is never taken from another section. Use [state] to move focus
 * into the grid from outside ([AppGridState.requestEntry]).
 *
 * **Edit mode** (when [onMove] is given): long-press OK on an app lifts it. Left and right swap with the
 * neighbour, up and down move it one grid row, OK drops it ([onMove] with its new index in [apps]), Back
 * cancels. Down from the last row moves focus to the strip under the grid with [onUnpin] and [onHide].
 *
 * @param installedCount shown on the "All apps" cell, when known.
 * @param focusAllAppsOnEnter puts focus on the "All apps" cell when the section is first shown (returning from
 *   the drawer).
 */
@Composable
fun AppGridSection(
    apps: List<LauncherApp>,
    onLaunch: (LauncherApp) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    state: AppGridState = rememberAppGridState(),
    onExitUp: (() -> Unit)? = null,
    onExitDown: (() -> Unit)? = null,
    onMove: ((app: LauncherApp, toIndex: Int) -> Unit)? = null,
    onUnpin: ((LauncherApp) -> Unit)? = null,
    onHide: ((LauncherApp) -> Unit)? = null,
    installedCount: Int? = null,
    focusAllAppsOnEnter: Boolean = false,
) {
    val columns = FieldDimens.appGridColumns
    val sound = LocalFieldSound.current
    val focusManager = LocalFocusManager.current
    val baseRow = LocalSoundRow.current

    var session by remember { mutableStateOf<EditSession<LauncherApp>?>(null) }
    var settling by remember { mutableStateOf<List<LauncherApp>?>(null) }
    val stripRequesters = remember { List(2) { FocusRequester() } }

    // While editing, the grid shows the working copy; just after a drop it keeps showing the dropped order
    // until the repository's list catches up.
    val shown = session?.working ?: settling ?: apps
    val cellCount = shown.size + 1
    val hasStrip = onUnpin != null || onHide != null

    SideEffect {
        state.cellCount = cellCount
        state.isEditing = session != null
    }
    LaunchedEffect(apps) { settling = null }
    LaunchedEffect(settling) {
        if (settling != null) {
            delay(SettleMillis)
            settling = null
        }
    }
    GridFocusRestore(state, apps) { state.focusVerified(it) }
    LaunchedEffect(focusAllAppsOnEnter) {
        if (focusAllAppsOnEnter) state.focusVerified(state.cellCount - 1)
    }

    // --- edit mode -----------------------------------------------------------------------------------------

    fun beginEdit(index: Int) {
        if (onMove == null || settling != null || index !in apps.indices) return
        session = EditSession.start(apps, index, columns)
        sound.select()
    }

    fun cancelEdit() {
        val s = session ?: return
        session = null
        sound.back()
        state.requestRestore(s.startIndex)
    }

    fun dropEdit() {
        val s = session ?: return
        session = null
        if (s.changed && onMove != null) {
            settling = s.working
            onMove(s.item, s.index)
        }
        sound.select()
        state.requestRestore(s.index)
    }

    fun runAction(action: (LauncherApp) -> Unit) {
        val s = session ?: return
        session = null
        sound.select()
        action(s.item)
        state.requestRestore(s.startIndex)
    }

    fun returnToLiftedTile() {
        val s = session ?: return
        state.requesters.tryRequest(s.index)
    }

    SideEffect { state.cancelEditAction = { cancelEdit() } }
    DisposableEffect(state) {
        onDispose {
            state.cancelEditAction = null
            state.isEditing = false
        }
    }
    BackHandler(enabled = session != null) { cancelEdit() }

    // --- keys ----------------------------------------------------------------------------------------------

    fun exitUp(repeatCount: Int) {
        if (onExitUp != null) onExitUp()
        else if (!focusManager.moveFocus(FocusDirection.Up) && repeatCount == 0) sound.edge()
    }

    fun exitDown(repeatCount: Int) {
        if (onExitDown != null) onExitDown()
        else if (!focusManager.moveFocus(FocusDirection.Down) && repeatCount == 0) sound.edge()
    }

    fun editStep(s: EditSession<LauncherApp>, direction: GridDirection, repeatCount: Int) {
        val next = s.move(direction)
        if (next.index != s.index) {
            session = next
            // The cell at the new index already exists; the lifted tile is drawn there on the next frame.
            state.requesters.tryRequest(next.index)
        } else if (direction == GridDirection.DOWN && hasStrip) {
            stripRequesters[0].tryRequest()
        } else if (repeatCount == 0) {
            sound.edge()
        }
    }

    fun onDirection(index: Int, direction: GridDirection, repeatCount: Int) {
        val s = session
        if (s != null) {
            editStep(s, direction, repeatCount)
            return
        }
        when (val move = gridMove(index, cellCount, columns, direction)) {
            is GridMove.Stay -> state.requesters.tryRequest(move.index)
            GridMove.Edge -> if (repeatCount == 0) sound.edge()
            GridMove.ExitUp -> exitUp(repeatCount)
            GridMove.ExitDown -> exitDown(repeatCount)
        }
    }

    fun onTap(index: Int) {
        if (session != null) {
            dropEdit()
            return
        }
        val app = shown.getOrNull(index)
        if (app != null) {
            launchApp(sound, app, onLaunch)
        } else {
            sound.select()
            onOpenDrawer()
        }
    }

    fun onLongPress(index: Int) {
        if (session != null) dropEdit() else beginEdit(index)
    }

    fun handleKey(index: Int, event: KeyEvent): Boolean {
        val direction = event.gridDirection()
        if (direction != null) {
            if (event.type == KeyEventType.KeyDown) {
                state.press.cancel()
                onDirection(index, direction, event.nativeKeyEvent.repeatCount)
            }
            return true
        }
        if (event.isActivateKey()) {
            if (event.type == KeyEventType.KeyDown) {
                val action = state.press.onDown(event.nativeKeyEvent.repeatCount, event.nativeKeyEvent.isLongPress)
                if (action == PressAction.LONG_PRESS) onLongPress(index)
            } else if (event.type == KeyEventType.KeyUp) {
                if (state.press.onUp() == PressAction.TAP) onTap(index)
            }
            return true
        }
        return false
    }

    // --- layout --------------------------------------------------------------------------------------------

    val rowCount = (cellCount + columns - 1) / columns

    Column(modifier.onFocusChanged { state.rootHasFocus = it.hasFocus }) {
        Column(verticalArrangement = Arrangement.spacedBy(FieldDimens.appGridRowGap)) {
            for (row in 0 until rowCount) {
                // The focus tick (played by fieldFocus) is pitched by the visual row.
                CompositionLocalProvider(LocalSoundRow provides baseRow + row) {
                    Row(horizontalArrangement = Arrangement.spacedBy(FieldDimens.appGridColumnGap)) {
                        val cellsInRow = minOf(columns, cellCount - row * columns)
                        for (col in 0 until cellsInRow) {
                            val index = row * columns + col
                            val cellModifier = Modifier
                                .focusRequester(state.requesters[index])
                                .onPreviewKeyEvent { handleKey(index, it) }
                            val app = shown.getOrNull(index)
                            if (app != null) {
                                AppTile(
                                    app = app,
                                    onLaunch = onLaunch,
                                    modifier = cellModifier,
                                    lifted = session?.index == index,
                                    onFocusChange = { focused -> if (focused) state.focusedIndex = index },
                                )
                            } else {
                                AllAppsTile(
                                    onOpen = onOpenDrawer,
                                    modifier = cellModifier,
                                    installedCount = installedCount,
                                    onFocusChange = { focused -> if (focused) state.focusedIndex = index },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (session != null && hasStrip) {
            Spacer(Modifier.height(16.dp))
            val actions = buildList<StripAction> {
                if (onUnpin != null) add(StripAction("Unpin") { runAction(onUnpin) })
                if (onHide != null) add(StripAction("Hide") { runAction(onHide) })
            }
            ActionStrip(
                actions = actions,
                hint = "ARROWS MOVE · OK DROP · BACK CANCEL",
                requesters = stripRequesters,
                onUp = { returnToLiftedTile() },
            )
        }
    }
}

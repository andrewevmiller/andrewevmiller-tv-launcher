package com.andrewevmiller.tvlauncher.home.apps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import com.andrewevmiller.tvlauncher.theme.FieldDimens

/**
 * Focus plumbing shared by the Home grid and the drawer: one [FocusRequester] per cell index, the last
 * focused index, and the "focus survives list changes" bookkeeping (BUILD_PLAN section 5.4).
 *
 * Hold one with [rememberAppGridState] to move focus into the grid from outside (entry from above or below),
 * to read the last focused column, or to cancel edit mode (for example when the Home button is pressed).
 */
@Stable
class AppGridState internal constructor() {
    internal val requesters = FocusRequesterPool()
    internal val press = PressTracker()

    /** Number of cells currently shown (apps, plus the All apps cell on Home). Written by the composables. */
    internal var cellCount: Int = 0

    /** The cell that last gained focus, -1 before any. */
    internal var focusedIndex: Int = -1

    /** Whether focus is anywhere inside the section (cells or the action strip). */
    internal var rootHasFocus: Boolean = false

    internal var cancelEditAction: (() -> Unit)? = null

    private var pendingRestore: Int? = null
    private var forcedRestore: Int? = null

    internal var restoreTick by mutableIntStateOf(0)
        private set

    /** True while a tile is lifted in edit mode (Home grid), or while the pin/hide actions are open (drawer). */
    var isEditing by mutableStateOf(false)
        internal set

    /** The column of the last focused cell: use it to pick the column to enter the next section from. */
    val lastColumn: Int
        get() = if (focusedIndex < 0) 0 else columnOf(focusedIndex, FieldDimens.appGridColumns)

    /** Focus the cell at [index]. False if there is no such cell or it is not on screen yet. */
    fun requestFocus(index: Int): Boolean = index in 0 until cellCount && requesters.tryRequest(index)

    /**
     * Focus enters the grid from outside: from above (the top row) when [fromTop], else from below (the bottom
     * row), on the column nearest [preferredColumn] (default: the column last focused in this grid).
     */
    fun requestEntry(fromTop: Boolean, preferredColumn: Int = lastColumn): Boolean {
        val target = entryIndex(cellCount, FieldDimens.appGridColumns, preferredColumn, fromTop)
        return target >= 0 && requestFocus(target)
    }

    /** Focus the "All apps" cell (the last cell of the Home grid), e.g. when returning from the drawer. */
    fun requestAllAppsFocus(): Boolean = requestFocus(cellCount - 1)

    /** Ends edit mode without keeping the move, like Back does. No-op when not editing. */
    fun cancelEdit() {
        cancelEditAction?.invoke()
    }

    /** Called from composition when the item list changed: remember to restore focus if the grid had it. */
    internal fun noteListChanged() {
        if (rootHasFocus && focusedIndex >= 0) pendingRestore = focusedIndex
    }

    /** Ask for focus to be put back on (a clamped) [index] regardless of who has it now. */
    internal fun requestRestore(index: Int) {
        forcedRestore = index
        restoreTick++
    }

    internal fun takePending(): Int? = pendingRestore.also { pendingRestore = null }.clamped()

    internal fun takeForced(): Int? = forcedRestore.also { forcedRestore = null }.clamped()

    private fun Int?.clamped(): Int? = this?.let { clampFocus(it, cellCount) }?.takeIf { it >= 0 }
}

@Composable
fun rememberAppGridState(): AppGridState = remember { AppGridState() }

/** Index-keyed requesters. Cells beyond the current count simply stay unattached. */
internal class FocusRequesterPool {
    private val map = HashMap<Int, FocusRequester>()

    operator fun get(index: Int): FocusRequester = map.getOrPut(index) { FocusRequester() }

    fun tryRequest(index: Int): Boolean = get(index).tryRequest()
}

/** requestFocus() throws when no focus target is attached to the requester yet (cell not composed). */
internal fun FocusRequester.tryRequest(): Boolean =
    try {
        requestFocus()
        true
    } catch (e: RuntimeException) {
        false
    }

/**
 * Puts focus on (a clamped) [index]. Waits a frame first so a list that just changed has been laid out, and
 * retries a few frames if the cell is not attached yet.
 */
internal suspend fun AppGridState.focusVerified(index: Int) {
    val target = clampFocus(index, cellCount)
    if (target < 0) return
    repeat(FOCUS_ATTEMPTS) {
        withFrameNanos { }
        if (requesters.tryRequest(target)) return
    }
}

private const val FOCUS_ATTEMPTS = 4

/**
 * Focus survival: when [items] changes and the section had focus beforehand, focus is re-requested on the cell
 * now at the previous index, clamped to the new size. Nothing happens on first composition or when the user's
 * focus is elsewhere (for example on the chips row while the filter changes).
 *
 * Also runs the explicit restores asked for with [AppGridState.requestRestore] (end of edit mode, closing the
 * action strip). [focusItem] does the actual focusing, so the drawer can scroll an item into view first.
 */
@Composable
internal fun GridFocusRestore(
    state: AppGridState,
    items: Any?,
    focusItem: suspend (Int) -> Unit,
) {
    val focus by rememberUpdatedState(focusItem)

    // Runs in composition, before the change is applied: the focused cell is still attached here.
    remember(items) {
        state.noteListChanged()
        0
    }
    LaunchedEffect(items) {
        state.takePending()?.let { focus(it) }
    }

    val tick = state.restoreTick
    LaunchedEffect(tick) {
        if (tick > 0) state.takeForced()?.let { focus(it) }
    }
}

// --- key mapping ---------------------------------------------------------------------------------------------

internal fun KeyEvent.gridDirection(): GridDirection? = when (key) {
    Key.DirectionLeft -> GridDirection.LEFT
    Key.DirectionRight -> GridDirection.RIGHT
    Key.DirectionUp -> GridDirection.UP
    Key.DirectionDown -> GridDirection.DOWN
    else -> null
}

internal fun KeyEvent.isActivateKey(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

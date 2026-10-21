package com.andrewevmiller.tvlauncher.home.apps

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.sound.LocalSoundRow
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.dotGrid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DrawerFocusAttempts = 4

/** If the OK key's release never arrives, move focus into the pin/hide strip anyway after this long. */
private const val StripFocusFallbackMillis = 1200L

/** The drawer's in-flight D-pad move, so key repeat keeps counting from where the last press was heading. */
private class DrawerNav {
    var job: Job? = null
    var cursor: Int = -1
}

/**
 * The full-screen "All apps" drawer (BUILD_PLAN section 5.4): every app in a [LazyVerticalGrid] with the same
 * columns, gaps and tile as Home, on the dot-grid page, inside the safe area. [header] sits under the title
 * (the integrator puts the chips row there); up from the first grid row moves focus into it.
 *
 * Back calls [onBack] (and plays `FieldSound.back()`). D-pad rules are the Home grid's ([gridMove]); because
 * lazy items leave composition, a move to a tile that is not on screen scrolls it into view before focusing
 * it, and the last focused index is remembered so focus can be restored after list changes.
 *
 * Long-press OK on a tile opens the action strip under the grid with "Pin" (or "Unpin" when the app is in
 * [pinned]) and "Hide", for whichever of [onPin], [onUnpin] and [onHide] are given. Back closes the strip.
 */
@Composable
fun AppDrawer(
    apps: List<LauncherApp>,
    onLaunch: (LauncherApp) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pinned: Set<ComponentName> = emptySet(),
    onPin: ((LauncherApp) -> Unit)? = null,
    onUnpin: ((LauncherApp) -> Unit)? = null,
    onHide: ((LauncherApp) -> Unit)? = null,
    state: AppGridState = rememberAppGridState(),
    header: @Composable () -> Unit = {},
) {
    val colors = Field.colors
    val columns = FieldDimens.appGridColumns
    val sound = LocalFieldSound.current
    val focusManager = LocalFocusManager.current
    val baseRow = LocalSoundRow.current
    val reduced = Field.reducedMotion
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val nav = remember { DrawerNav() }
    val stripRequesters = remember { List(2) { FocusRequester() } }

    var actionsFor by remember { mutableStateOf<LauncherApp?>(null) }
    var stripFocusPending by remember { mutableStateOf(false) }

    SideEffect {
        state.cellCount = apps.size
        state.isEditing = actionsFor != null
    }

    // --- focus ---------------------------------------------------------------------------------------------

    /** Focuses the tile at [target], scrolling it into view first when it is not composed. */
    suspend fun focusItem(target: Int, towards: GridDirection?) {
        if (target !in apps.indices) return
        fun visible() = gridState.layoutInfo.visibleItemsInfo.any { it.index == target }
        // Fast path: on screen, so focus it now with no frame of delay on a D-pad press.
        if (visible() && state.requesters.tryRequest(target)) return
        if (!visible()) {
            val rowPx = with(density) { (FieldDimens.bannerHeight + FieldDimens.appGridRowGap).toPx() }
            val delta = when (towards) {
                GridDirection.DOWN -> rowPx
                GridDirection.UP -> -rowPx
                else -> 0f
            }
            if (delta != 0f) {
                if (reduced) gridState.scrollBy(delta) else gridState.animateScrollBy(delta)
            }
            if (!visible()) gridState.scrollToItem(target)
        }
        repeat(DrawerFocusAttempts) {
            withFrameNanos { }
            if (state.requesters.tryRequest(target)) return
        }
    }

    suspend fun focusStrip() {
        repeat(DrawerFocusAttempts) {
            withFrameNanos { }
            if (stripRequesters[0].tryRequest()) return
        }
    }

    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty() && state.focusedIndex < 0) focusItem(0, null)
    }
    GridFocusRestore(state, apps) { focusItem(it, null) }

    // --- pin / hide actions --------------------------------------------------------------------------------

    fun closeActions(withSound: Boolean) {
        val app = actionsFor ?: return
        actionsFor = null
        stripFocusPending = false
        if (withSound) sound.back()
        state.requestRestore(apps.indexOfFirst { it.component == app.component }.coerceAtLeast(0))
    }

    fun runAction(app: LauncherApp, action: (LauncherApp) -> Unit) {
        val index = apps.indexOfFirst { it.component == app.component }.coerceAtLeast(0)
        actionsFor = null
        stripFocusPending = false
        sound.select()
        action(app)
        state.requestRestore(index)
    }

    fun stripActionsFor(app: LauncherApp): List<StripAction> = buildList<StripAction> {
        if (app.component in pinned) {
            if (onUnpin != null) add(StripAction("Unpin") { runAction(app, onUnpin) })
        } else if (onPin != null) {
            add(StripAction("Pin") { runAction(app, onPin) })
        }
        if (onHide != null) add(StripAction("Hide") { runAction(app, onHide) })
    }

    fun openActions(index: Int) {
        val app = apps.getOrNull(index) ?: return
        if (stripActionsFor(app).isEmpty()) return
        actionsFor = app
        // Focus moves into the strip once OK is released; moving it mid-press would turn the release into a click.
        stripFocusPending = true
        sound.select()
    }

    LaunchedEffect(actionsFor) {
        if (actionsFor != null) {
            delay(StripFocusFallbackMillis)
            if (stripFocusPending) {
                stripFocusPending = false
                focusStrip()
            }
        }
    }

    // Registered first, so the strip's handler below wins while the strip is open.
    BackHandler {
        sound.back()
        onBack()
    }
    BackHandler(enabled = actionsFor != null) { closeActions(true) }

    // --- keys ----------------------------------------------------------------------------------------------

    fun onDirection(index: Int, direction: GridDirection, repeatCount: Int) {
        // While a scroll is still carrying focus to an earlier target, count from that target.
        val from = if (nav.job?.isActive == true && nav.cursor in apps.indices) nav.cursor else index
        when (val move = gridMove(from, apps.size, columns, direction)) {
            is GridMove.Stay -> {
                nav.cursor = move.index
                nav.job?.cancel()
                nav.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { focusItem(move.index, direction) }
            }
            GridMove.Edge -> if (repeatCount == 0) sound.edge()
            GridMove.ExitUp -> if (!focusManager.moveFocus(FocusDirection.Up) && repeatCount == 0) sound.edge()
            GridMove.ExitDown -> if (repeatCount == 0) sound.edge()
        }
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
                if (action == PressAction.LONG_PRESS) openActions(index)
            } else if (event.type == KeyEventType.KeyUp) {
                if (state.press.onUp() == PressAction.TAP) {
                    apps.getOrNull(index)?.let { launchApp(sound, it, onLaunch) }
                }
                if (stripFocusPending) {
                    stripFocusPending = false
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { focusStrip() }
                }
            }
            return true
        }
        return false
    }

    // --- layout --------------------------------------------------------------------------------------------

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .dotGrid(colors.line)
            .padding(horizontal = FieldDimens.safeHorizontal, vertical = FieldDimens.safeVertical),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = "All apps", style = Field.type.heading, color = colors.ink)
            Spacer(Modifier.width(FieldDimens.appGridColumnGap))
            Text(
                text = "EVERY APP · A–Z · ${apps.size}",
                style = Field.type.label,
                color = colors.muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        header()
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(FieldDimens.hairline)
                .background(colors.line),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            // Only the grid counts for focus survival: the header chips filter the list, and focus must stay on them.
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { state.rootHasFocus = it.hasFocus },
            // Room above and below for the focused tile's scale-up.
            contentPadding = PaddingValues(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(FieldDimens.appGridColumnGap),
            verticalArrangement = Arrangement.spacedBy(FieldDimens.appGridRowGap),
        ) {
            itemsIndexed(apps, key = { _, app -> app.component.flattenToString() }) { index, app ->
                CompositionLocalProvider(LocalSoundRow provides baseRow + rowOf(index, columns)) {
                    AppTile(
                        app = app,
                        onLaunch = onLaunch,
                        modifier = Modifier
                            .focusRequester(state.requesters[index])
                            .onPreviewKeyEvent { handleKey(index, it) },
                        onFocusChange = { focused ->
                            if (focused) {
                                state.focusedIndex = index
                                nav.cursor = index
                            }
                        },
                    )
                }
            }
        }
        val target = actionsFor
        if (target != null) {
            ActionStrip(
                actions = stripActionsFor(target),
                hint = "${target.label.uppercase()} · OK APPLY · BACK CLOSE",
                requesters = stripRequesters,
                onUp = { closeActions(true) },
            )
        }
    }
}

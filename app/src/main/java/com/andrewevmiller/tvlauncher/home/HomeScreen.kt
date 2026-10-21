package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.sound.LocalSoundRow
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.dotGrid
import com.andrewevmiller.tvlauncher.theme.fieldTile

/** Inputs and system shortcuts at the bottom of Home. */
@Immutable
data class SystemTile(val label: String, val icon: ImageVector, val onClick: () -> Unit)

enum class Chip(val label: String, val category: AppCategory?) {
    ALL("All", null), VIDEO("Video", AppCategory.VIDEO), MUSIC("Music", AppCategory.MUSIC),
    GAMES("Games", AppCategory.GAMES), TOOLS("Tools", AppCategory.TOOLS),
}

private const val COLUMNS = 4
private val gridColumnGap = 32.dp
private val gridRowGap = 20.dp

private sealed interface Cell {
    data class App(val app: LauncherApp) : Cell
    data class All(val count: Int) : Cell
}

/**
 * Home and the all-apps drawer, laid out to the mockup (960x540dp): header, chips, a four-column
 * app grid ending in an All apps tile, then system tiles. The drawer is the same frame with every app A-Z.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    allApps: List<LauncherApp>,
    systemTiles: List<SystemTile>,
    chip: Chip,
    drawer: Boolean,
    onChip: (Chip) -> Unit,
    onOpenDrawer: () -> Unit,
    onLaunch: (LauncherApp) -> Unit,
    feature: Feature?,
    recent: List<RecentEntry>,
    recentOpen: Boolean,
    onToggleRecent: () -> Unit,
    order: List<String>,
    editingPkg: String?,
    onLongPress: (LauncherApp) -> Unit,
    onMove: (LauncherApp, Int) -> Unit,
    onEditEnd: () -> Unit,
    pinnedSet: Set<String>?,
    menuApp: LauncherApp?,
    onMenuPick: (MenuAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Shows the LAUNCHER SETTINGS chip in the header when set (the Menu key opens Settings too). */
    onOpenSettings: (() -> Unit)? = null,
    /** The screensaver tiles (Settings → Screensaver has the choice; these start one now). Null hides the row. */
    screensaver: (@Composable () -> Unit)? = null,
    /** Scales the dot grid (Settings → Appearance), because panels crush near-black. */
    dotAlpha: Float = 1f,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    val focusManager = LocalFocusManager.current
    val dotColor = colors.line.copy(alpha = colors.line.alpha * dotAlpha.coerceIn(0f, 1f))
    val matches = { a: LauncherApp -> chip.category == null || a.category == chip.category }
    val apps = if (drawer) allApps.filter(matches) else orderPinned(allApps, order, pinnedSet).filter(matches)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val gesture = remember { OkGesture() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Focus only scrolls the minimum needed, which leaves the header cut off when coming back up.
    // The band and chips sit at the top, so landing on them shows the whole top of the page.
    // While the long-press menu is up, and just after it closes, focus passes through the top of the
    // page on its way back to the tile; that must not drag the page up with it.
    val scrollGuard = remember { ScrollGuard() }
    if (menuApp != null) scrollGuard.holding = true
    val toTop = Modifier.onFocusChanged {
        if (it.hasFocus && !scrollGuard.holding) scope.launch { listState.animateScrollToItem(0) }
    }
    // The built-in focus scroll is switched off below: it and the scrolls above fought each other,
    // which read as jumpiness. This is the only scroll now: one eased move that keeps the focused
    // thing 40dp inside the screen. A new move starts from wherever the last one had got to.
    val density = LocalDensity.current
    fun reveal(itemKey: String, top: Float = 0f, height: Float = -1f) {
        scope.launch {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == itemKey }
            if (item == null) return@launch
            val margin = with(density) { 40.dp.toPx() }
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            val t = item.offset + top
            val b = t + (if (height < 0) item.size.toFloat() else height)
            val dy = when {
                t < margin -> t - margin
                b > viewport - margin -> b - (viewport - margin)
                else -> 0f
            }
            if (dy != 0f) listState.animateScrollBy(dy, tween(220, easing = FastOutSlowInEasing))
        }
    }
    val isPinned = { a: LauncherApp -> pinnedSet?.contains(a.component.packageName) ?: false }
    // After the menu closes the tile gets focus back (Move needs it for the arrow keys).
    var lastMenuPkg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(menuApp) {
        if (menuApp != null) lastMenuPkg = menuApp.component.packageName
        else lastMenuPkg?.let {
            awaitFrame()
            runCatching { requesters[it]?.requestFocus() }
            awaitFrame()
            awaitFrame()
            scrollGuard.holding = false
        }
    }
    val cells: List<Cell> = apps.map { Cell.App(it) } + if (drawer) emptyList() else listOf(Cell.All(allApps.size))
    val gridRows = cells.chunked(COLUMNS)
    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .dotGrid(dotColor)
            // Arrow keys nobody below handled: move focus ourselves so we know when it can't move.
            .onKeyEvent { event ->
                val direction = event.focusDirection() ?: return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val moved = focusManager.moveFocus(direction)
                    if (!moved && event.nativeKeyEvent.repeatCount == 0) sound.edge()
                }
                true
            },
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = FieldDimens.safeHorizontal, end = FieldDimens.safeHorizontal,
                top = FieldDimens.safeVertical, bottom = 40.dp,
            ),
        ) {
            item("header") {
                ClockHeader(if (drawer) "All apps" else "Home", onOpenSettings = onOpenSettings)
                Spacer(Modifier.height(18.dp))
            }
            if (feature != null && !drawer) {
                item("band") {
                    CompositionLocalProvider(LocalSoundRow provides 0) {
                        FeatureBand(feature, onOpen = { onLaunch(feature.app) }, modifier = toTop)
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
            item("chips") {
                CompositionLocalProvider(LocalSoundRow provides 1) {
                    Row(toTop.padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Chip.entries.forEach { c -> ChipPill(c.label, selected = c == chip) { onChip(c) } }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            item("apps-head") {
                SectionHead(if (drawer) "Every app · A–Z · ${apps.size}" else "Apps · ${apps.size} pinned")
                if (!drawer && apps.isEmpty() && chip == Chip.ALL) {
                    Text(
                        "Nothing is pinned yet. Open All apps, hold OK on an app and choose Pin to Home.",
                        style = Field.type.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
                        color = colors.muted,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
            }
            // One block with every tile placed by index. A tile keeps its identity (and focus) when it
            // changes place, and slides to its new spot instead of being rebuilt in another row.
            item("apps-grid") {
              CompositionLocalProvider(LocalSoundRow provides 2) {
                val rowCount = gridRows.size
                val stepX = FieldDimens.bannerWidth + gridColumnGap
                val stepY = FieldDimens.bannerHeight + gridRowGap
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(stepY * rowCount),
                ) {
                    cells.forEachIndexed { index, cell ->
                        val id = when (cell) {
                            is Cell.App -> cell.app.component.flattenToString()
                            is Cell.All -> "all"
                        }
                        key(id) {
                            val spec = if (Field.reducedMotion) snap<Dp>() else tween(durationMillis = 180)
                            val x by animateDpAsState(stepX * (index % COLUMNS), spec, label = "tileX")
                            val y by animateDpAsState(stepY * (index / COLUMNS), spec, label = "tileY")
                            val rowTop = with(density) { (stepY * (index / COLUMNS)).toPx() }
                            val rowHeight = with(density) { FieldDimens.bannerHeight.toPx() }
                            Box(
                                Modifier
                                    .offset(x, y)
                                    .onFocusChanged {
                                        // Stray focus while the menu closes is ignored; only the tile the menu was for may scroll.
                                        val mine = (cell as? Cell.App)?.app?.component?.packageName == lastMenuPkg
                                        if (it.hasFocus && (!scrollGuard.holding || mine)) reveal("apps-grid", rowTop, rowHeight)
                                    },
                            ) {
                                when (cell) {
                                    is Cell.App -> AppTile(
                                        app = cell.app,
                                        requester = requesters.getOrPut(cell.app.component.packageName) { FocusRequester() },
                                        gesture = gesture,
                                        reorderable = true,
                                        editing = editingPkg != null,
                                        lifted = editingPkg == cell.app.component.packageName,
                                        onMove = { onMove(cell.app, it) },
                                        onLongPress = { onLongPress(cell.app) },
                                        onEditEnd = onEditEnd,
                                        onClick = { onLaunch(cell.app) },
                                    )
                                    is Cell.All -> AllAppsTile(cell.count, onOpenDrawer)
                                }
                                if (drawer && cell is Cell.App && isPinned(cell.app)) PinBadge()
                            }
                        }
                    }
                }
              }
            }
            if (!drawer && recent.isNotEmpty()) {
                item("recent") {
                    Column(Modifier.onFocusChanged { if (it.hasFocus) reveal("recent") }) {
                        SectionHead(if (recentOpen) "Recent" else "Recent · ${recent.size} hidden")
                        CompositionLocalProvider(LocalSoundRow provides 3) {
                            RecentRow(recent, recentOpen, onToggleRecent, onLaunch)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            if (!drawer && screensaver != null) {
                item("screensaver") {
                    Column(Modifier.onFocusChanged { if (it.hasFocus) reveal("screensaver") }) {
                        SectionHead("Screensaver · start one now")
                        CompositionLocalProvider(LocalSoundRow provides 4) { screensaver() }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            if (!drawer && systemTiles.isNotEmpty()) {
                item("system") {
                    Column(Modifier.onFocusChanged { if (it.hasFocus) reveal("system") }) {
                        SectionHead("Inputs & system")
                        CompositionLocalProvider(LocalSoundRow provides 5) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                systemTiles.forEach { SystemTileView(it) }
                            }
                        }
                    }
                }
            }
        }
        }
        if (menuApp != null) {
            val onHome = !drawer && chip == Chip.ALL && isPinned(menuApp)
            val actions = buildList {
                if (onHome) add(MenuAction.MOVE)
                add(if (isPinned(menuApp)) MenuAction.UNPIN else MenuAction.PIN)
            }
            PinMenu(menuApp, actions) {
                gesture.longFired = false
                onMenuPick(it)
            }
        }
    }
}

private fun KeyEvent.focusDirection(): FocusDirection? = when (key) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    Key.DirectionLeft -> FocusDirection.Left
    Key.DirectionRight -> FocusDirection.Right
    else -> null
}

/** Turns off the automatic scroll-on-focus; HomeScreen scrolls itself so there is only one motion. */
@OptIn(ExperimentalFoundationApi::class)
private val NoAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@Composable
private fun SectionHead(label: String) {
    val colors = Field.colors
    Box(Modifier.fillMaxWidth().height(FieldDimens.hairline).background(colors.line))
    Spacer(Modifier.height(10.dp))
    Text(
        label.uppercase(),
        style = Field.type.label.copy(fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
        color = colors.muted,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Field.colors
    Text(
        label,
        style = Field.type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
        color = if (selected) colors.ink else colors.muted,
        modifier = Modifier
            .fieldTile(onClick, FieldShapes.chip)
            .background(colors.bgSoft, FieldShapes.chip)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

@Composable
private fun AppTile(
    app: LauncherApp,
    requester: FocusRequester,
    gesture: OkGesture,
    reorderable: Boolean,
    editing: Boolean,
    lifted: Boolean,
    onMove: (Int) -> Unit,
    onLongPress: () -> Unit,
    onEditEnd: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = Field.colors
    val art = app.artwork
    val fullBleed = art != null && app.artworkIsBanner
    Box(
        Modifier
            .size(FieldDimens.bannerWidth, FieldDimens.bannerHeight)
            // A lifted tile tips and grows a little more, still the one focused element.
            .graphicsLayer {
                if (lifted) {
                    scaleX = 1.04f
                    scaleY = 1.04f
                    rotationZ = -1.4f
                }
            }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                val down = e.type == KeyEventType.KeyDown
                when {
                    editing -> when {
                        ok && down -> true
                        ok -> {
                            onEditEnd()
                            true
                        }
                        !down -> false
                        e.key == Key.DirectionLeft -> { onMove(-1); true }
                        e.key == Key.DirectionRight -> { onMove(1); true }
                        e.key == Key.DirectionUp -> { onMove(-COLUMNS); true }
                        e.key == Key.DirectionDown -> { onMove(COLUMNS); true }
                        else -> false
                    }
                    ok && reorderable -> {
                        if (down) {
                            // A new press starts clean: the release of the one that opened the menu may never reach this tile.
                            if (e.nativeKeyEvent.repeatCount == 0) gesture.longFired = false
                            if (e.nativeKeyEvent.repeatCount > 0 && !gesture.longFired) {
                                gesture.longFired = true
                                onLongPress()
                            }
                        } else if (!gesture.longFired) onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(requester)
            .fieldTile(onClick, bleed = fullBleed)
            .clip(FieldShapes.tile)
            .background(colors.bgSoft, FieldShapes.tile),
    ) {
        if (art != null && app.artworkIsBanner) {
            Image(art, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (art != null) Image(art, null, Modifier.size(44.dp)) else Spacer(Modifier.size(44.dp))
                Column {
                    Text(
                        app.label,
                        style = Field.type.bodyStrong.copy(fontSize = 14.sp, lineHeight = 17.sp),
                        color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        app.category.name,
                        style = Field.type.caption.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.06.em),
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllAppsTile(count: Int, onClick: () -> Unit) {
    val colors = Field.colors
    Column(
        Modifier
            .size(FieldDimens.bannerWidth, FieldDimens.bannerHeight)
            .fieldTile(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Image(FieldIcons.Grid, null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(colors.muted))
        Column {
            Text("All apps", style = Field.type.bodyStrong.copy(fontSize = 14.sp, lineHeight = 17.sp), color = colors.ink)
            Text(
                "$count INSTALLED",
                style = Field.type.caption.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.06.em),
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun SystemTileView(tile: SystemTile) {
    val colors = Field.colors
    Row(
        Modifier.size(120.dp, 64.dp).fieldTile(tile.onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(tile.icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(colors.muted))
        Text(tile.label, style = Field.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 16.sp), color = colors.ink, maxLines = 2)
    }
}

/** Shared across tiles because a moving tile is recreated: has a long press just begun an edit? */
class OkGesture { var longFired = false }

/** Home order: saved order first, anything new after it in A-Z order. */
fun orderPinned(apps: List<LauncherApp>, order: List<String>, pinnedSet: Set<String>?): List<LauncherApp> =
    apps.filter { pinnedSet?.contains(it.component.packageName) ?: false }.sortedBy { a -> order.indexOf(a.component.packageName).let { if (it < 0) Int.MAX_VALUE else it } }

/** A small pin on drawer tiles for apps that are on Home. */
@Composable
private fun BoxScope.PinBadge() {
    val colors = Field.colors
    Image(
        FieldIcons.Pin, null,
        Modifier.align(Alignment.TopEnd).padding(8.dp).background(colors.bg.copy(alpha = 0.75f), FieldShapes.chip).padding(4.dp).size(14.dp),
        colorFilter = ColorFilter.tint(colors.ink),
    )
}

/** True while a focus detour (the long-press menu) must not scroll the page to the top. */
class ScrollGuard { var holding = false }

package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
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
    onReorderStart: (LauncherApp) -> Unit,
    onMove: (LauncherApp, Int) -> Unit,
    onEditEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val matches = { a: LauncherApp -> chip.category == null || a.category == chip.category }
    val apps = if (drawer) allApps.filter(matches) else orderPinned(allApps, order).filter(matches)
    // Tiles are recreated when they move between rows, so focus is put back on the moved tile by key.
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val gesture = remember { OkGesture() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Focus only scrolls the minimum needed, which leaves the header cut off when coming back up.
    // The band and chips sit at the top, so landing on them shows the whole top of the page.
    val toTop = Modifier.onFocusChanged {
        if (it.hasFocus) scope.launch {
            // Wait out the focus bring-into-view scroll, which would otherwise win.
            awaitFrame()
            awaitFrame()
            listState.animateScrollToItem(0)
        }
    }
    val reorderable = !drawer && chip == Chip.ALL
    LaunchedEffect(apps.map { it.component.packageName }, editingPkg) {
        if (editingPkg != null) {
            awaitFrame()
            runCatching { requesters[editingPkg]?.requestFocus() }
        }
    }
    val cells: List<Cell> = apps.map { Cell.App(it) } + if (drawer) emptyList() else listOf(Cell.All(allApps.size))
    val gridRows = cells.chunked(COLUMNS)
    Box(modifier.fillMaxSize().background(colors.bg).dotGrid(colors.line)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = FieldDimens.safeHorizontal, end = FieldDimens.safeHorizontal,
                top = FieldDimens.safeVertical, bottom = 40.dp,
            ),
        ) {
            item("header") {
                ClockHeader(if (drawer) "All apps" else "Home")
                Spacer(Modifier.height(18.dp))
            }
            if (feature != null && !drawer) {
                item("band") {
                    FeatureBand(feature, onOpen = { onLaunch(feature.app) }, modifier = toTop)
                    Spacer(Modifier.height(18.dp))
                }
            }
            item("chips") {
                Row(toTop.padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Chip.entries.forEach { c -> ChipPill(c.label, selected = c == chip) { onChip(c) } }
                }
                Spacer(Modifier.height(10.dp))
            }
            item("apps-head") {
                SectionHead(if (drawer) "Every app · A–Z · ${apps.size}" else "Apps · ${apps.size} pinned")
            }
            items(gridRows.size, key = { "apps-$it" }) { i ->
                Row(
                    Modifier.padding(start = 6.dp, end = 6.dp, bottom = gridRowGap),
                    horizontalArrangement = Arrangement.spacedBy(gridColumnGap),
                ) {
                    gridRows[i].forEach { cell ->
                        when (cell) {
                            is Cell.App -> AppTile(
                                app = cell.app,
                                requester = requesters.getOrPut(cell.app.component.packageName) { FocusRequester() },
                                gesture = gesture,
                                reorderable = reorderable,
                                editing = editingPkg != null,
                                lifted = editingPkg == cell.app.component.packageName,
                                onMove = { onMove(cell.app, it) },
                                onReorderStart = { onReorderStart(cell.app) },
                                onEditEnd = onEditEnd,
                                onClick = { onLaunch(cell.app) },
                            )
                            is Cell.All -> AllAppsTile(cell.count, onOpenDrawer)
                        }
                    }
                }
            }
            if (!drawer && recent.isNotEmpty()) {
                item("recent") {
                    SectionHead(if (recentOpen) "Recent" else "Recent · ${recent.size} hidden")
                    RecentRow(recent, recentOpen, onToggleRecent, onLaunch)
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (!drawer && systemTiles.isNotEmpty()) {
                item("system") {
                    SectionHead("Inputs & system")
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        systemTiles.forEach { SystemTileView(it) }
                    }
                }
            }
        }
    }
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
    onReorderStart: () -> Unit,
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
                            // The release that ends the long press that started this edit is not a drop.
                            if (gesture.longFired) gesture.longFired = false else onEditEnd()
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
                            if (e.nativeKeyEvent.repeatCount > 0 && !gesture.longFired) {
                                gesture.longFired = true
                                onReorderStart()
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
fun orderPinned(apps: List<LauncherApp>, order: List<String>): List<LauncherApp> =
    apps.filter { it.pinned }.sortedBy { a -> order.indexOf(a.component.packageName).let { if (it < 0) Int.MAX_VALUE else it } }

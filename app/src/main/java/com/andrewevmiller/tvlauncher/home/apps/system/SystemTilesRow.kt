package com.andrewevmiller.tvlauncher.home.apps.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.fieldTile
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcon
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

// Tile geometry from the mockup's `.sys`: 120x64, icon + label side by side. The width grows for longer
// labels ("Settings", a custom input name) up to a cap, then the label ellipsizes.
private val TileMinWidth = 120.dp
private val TileMaxWidth = 220.dp
private val TileHeight = 64.dp
private val TileHorizontalPadding = 14.dp
private val IconLabelGap = 10.dp

/** How long the line under the row stays up. */
private const val NOTE_MILLIS = 5000L

private data class RowNote(val text: String, val serial: Int)

/**
 * The "Inputs & system" row: one compact tile per physical TV input (HDMI first), then Wi-Fi, Search and
 * Settings. A horizontal [LazyRow] (this row stays a carousel). HomeScreen supplies the row label, the
 * hairline above it and the safe-area padding.
 *
 * Pressing a tile plays `FieldSound.select()` and then:
 *  - by default ([onLaunch] = null): runs [SystemActions.launch]. If nothing could be started, a short
 *    muted mono line under the row says so ("Couldn't open HDMI 1."); if a fallback landed near the
 *    target ("Opened the TV app. Choose HDMI 1 there.") it says that. The line clears itself after a
 *    few seconds;
 *  - with an [onLaunch]: calls it instead and shows nothing (the caller owns the outcome).
 *
 * [tiles] is for previews and tests; by default the row builds them from the TV's real inputs and
 * keeps them current.
 */
@Composable
fun SystemTilesRow(
    modifier: Modifier = Modifier,
    onLaunch: ((SystemTile) -> Unit)? = null,
    tiles: List<SystemTile>? = null,
) {
    val context = LocalContext.current
    val sound = LocalFieldSound.current
    val liveTiles = rememberLiveSystemTiles(enabled = tiles == null)
    var note by remember { mutableStateOf<RowNote?>(null) }
    var serial by remember { mutableStateOf(0) }

    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MILLIS)
            note = null
        }
    }

    SystemTilesContent(
        tiles = tiles ?: liveTiles,
        note = note?.text,
        modifier = modifier,
        onTileClick = { tile ->
            sound.select()
            if (onLaunch != null) {
                onLaunch(tile)
            } else {
                val text = when (val result = SystemActions.launch(context, tile.action, tile.label)) {
                    is LaunchResult.Failed -> result.message
                    is LaunchResult.Started ->
                        if (result.approximate) approximateNote(tile.action, tile.label) else null
                }
                serial += 1
                note = if (text != null) RowNote(text, serial) else null
            }
        },
    )
}

/** The stateless row: tiles, plus the line under it. Previewed below. */
@Composable
internal fun SystemTilesContent(
    tiles: List<SystemTile>,
    note: String?,
    onTileClick: (SystemTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap),
            // Room for the focused tile's scale-up (1.06) and its 3dp border so neither is clipped.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            items(tiles, key = { it.key }) { tile ->
                SystemTileView(tile = tile, onClick = { onTileClick(tile) })
            }
        }
        // Space is always reserved so the rows below never jump when the line appears.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .padding(horizontal = 8.dp),
        ) {
            if (note != null) {
                Text(text = note, style = Field.type.caption, color = Field.colors.muted)
            }
        }
    }
}

@Composable
private fun SystemTileView(tile: SystemTile, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .widthIn(min = TileMinWidth, max = TileMaxWidth)
            .height(TileHeight)
            // Must precede fieldTile so it observes the focus target that fieldTile adds.
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .fieldTile(onClick = onClick)
            .padding(horizontal = TileHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(IconLabelGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldIcon(
            icon = tile.icon.toImageVector(),
            tint = if (focused) Field.colors.accent else Field.colors.muted,
        )
        Text(
            text = tile.label,
            style = Field.type.label,
            color = Field.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun SystemIconKey.toImageVector(): ImageVector = when (this) {
    SystemIconKey.PORT -> FieldIcons.Port
    SystemIconKey.WIFI -> FieldIcons.Wifi
    SystemIconKey.SEARCH -> FieldIcons.Search
    SystemIconKey.GEAR -> FieldIcons.Gear
}

/**
 * The real row. The first composition already shows the inputs (one quick synchronous read), so the row
 * never appears with only Wi-Fi/Search/Settings and then grows, which would leave D-pad focus on the
 * wrong tile when it is scrolled into view. After that it follows input changes and re-reads every time
 * the launcher is started again (so a rename in TV settings shows up). With [enabled] false nothing is
 * read or collected and no TvInputManager is touched.
 */
@Composable
private fun rememberLiveSystemTiles(enabled: Boolean): List<SystemTile> {
    val context = LocalContext.current
    val source = remember(context, enabled) { if (enabled) TvInputSource(context) else null }
    val initial = remember(source) {
        if (source != null) buildSystemTiles(source.readNow()) else FixedSystemTiles
    }
    val flow = remember(source) { source?.tiles() ?: emptyFlow<List<SystemTile>>() }
    val live by flow.collectAsStateWithLifecycle(initialValue = initial)
    return live
}

// ---- previews: 2 HDMI inputs + Wi-Fi, Search, Settings ---------------------------------------------

private val previewTiles: List<SystemTile> = buildSystemTiles(
    listOf(
        InputDescriptor(id = "preview/HW1", type = TvInputType.HDMI, customLabel = null, hdmiPort = 1),
        InputDescriptor(id = "preview/HW2", type = TvInputType.HDMI, customLabel = "Apple TV", hdmiPort = 2),
    ),
)

@Preview(widthDp = 960, heightDp = 130)
@Composable
private fun SystemTilesRowPreview() {
    FieldTheme {
        Box(
            Modifier
                .background(Field.colors.bg)
                .padding(horizontal = FieldDimens.safeHorizontal, vertical = 8.dp),
        ) {
            SystemTilesContent(tiles = previewTiles, note = null, onTileClick = {})
        }
    }
}

@Preview(widthDp = 960, heightDp = 130)
@Composable
private fun SystemTilesRowFailurePreview() {
    FieldTheme {
        Box(
            Modifier
                .background(Field.colors.bg)
                .padding(horizontal = FieldDimens.safeHorizontal, vertical = 8.dp),
        ) {
            SystemTilesContent(tiles = previewTiles, note = "Couldn't open HDMI 1.", onTileClick = {})
        }
    }
}

package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.sound.LocalSoundRow
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.dotGrid

/**
 * One row on the home screen. A null [label] draws the row bare (no hairline, no heading), which is how
 * the chips row sits directly under the feature band. [content] lays out its own horizontal list.
 */
@Immutable
data class HomeRow(
    val key: String,
    val label: String?,
    val content: @Composable () -> Unit,
)

/**
 * The row-based home frame (used by [HomeContent]): dot-grid page, header, optional feature band, then rows separated by hairlines
 * (the site's `.rule`). Each slot is filled by a feature module; this file owns layout, the per-row sound
 * pitch ([LocalSoundRow]) and the edge thud for D-pad presses that can't move focus.
 *
 * [dotAlpha] scales the dot grid (Settings → Appearance), because panels crush near-black.
 */
@Composable
fun HomeFrame(
    rows: List<HomeRow>,
    modifier: Modifier = Modifier,
    dotAlpha: Float = 1f,
    listState: LazyListState = rememberLazyListState(),
    header: @Composable () -> Unit = { ClockHeader() },
    feature: (@Composable () -> Unit)? = null,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    val focusManager = LocalFocusManager.current
    val dotColor = colors.line.copy(alpha = colors.line.alpha * dotAlpha.coerceIn(0f, 1f))
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
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                horizontal = FieldDimens.safeHorizontal,
                vertical = FieldDimens.safeVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(FieldDimens.rowGap),
        ) {
            item(key = "header") { header() }
            if (feature != null) {
                item(key = "feature") {
                    CompositionLocalProvider(LocalSoundRow provides 0) { feature() }
                }
            }
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                CompositionLocalProvider(LocalSoundRow provides index + 1) { HomeRowFrame(row) }
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

@Composable
private fun HomeRowFrame(row: HomeRow) {
    val colors = Field.colors
    Column(Modifier.fillMaxWidth()) {
        if (row.label != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(FieldDimens.hairline)
                    .background(colors.line),
            )
            Spacer(Modifier.height(12.dp))
            Text(row.label.uppercase(), style = Field.type.label, color = colors.muted)
            Spacer(Modifier.height(12.dp))
        }
        row.content()
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun HomeFramePreview() {
    FieldTheme {
        HomeFrame(
            rows = listOf(
                HomeRow("a", "Apps · 6 pinned") { Text("Rows are filled by feature modules.", style = Field.type.body, color = Field.colors.ink) },
                HomeRow("b", "Inputs & system") { Text("HDMI 1 · HDMI 2 · Wi-Fi · Search · Settings", style = Field.type.label, color = Field.colors.muted) },
            ),
        )
    }
}

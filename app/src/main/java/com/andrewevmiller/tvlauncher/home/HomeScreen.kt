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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.dotGrid

/** One labelled row on the home screen. [content] lays out its own horizontal list. */
@Immutable
data class HomeRow(
    val key: String,
    val label: String,
    val content: @Composable () -> Unit,
)

/**
 * The home screen frame: dot-grid page, header, optional feature band, then rows
 * separated by hairlines (the site's `.rule`). Each slot is filled by a feature module;
 * this file only owns layout.
 */
@Composable
fun HomeScreen(
    rows: List<HomeRow>,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = { ClockHeader() },
    feature: (@Composable () -> Unit)? = null,
) {
    val colors = Field.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .dotGrid(colors.line),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                horizontal = FieldDimens.safeHorizontal,
                vertical = FieldDimens.safeVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(FieldDimens.rowGap),
        ) {
            item(key = "header") { header() }
            if (feature != null) item(key = "feature") { feature() }
            items(rows, key = { it.key }) { row -> HomeRowFrame(row) }
        }
    }
}

@Composable
private fun HomeRowFrame(row: HomeRow) {
    val colors = Field.colors
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(FieldDimens.hairline)
                .background(colors.line),
        )
        Spacer(Modifier.height(12.dp))
        Text(row.label.uppercase(), style = Field.type.label, color = colors.muted)
        Spacer(Modifier.height(12.dp))
        row.content()
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun HomeScreenPreview() {
    FieldTheme {
        HomeScreen(rows = placeholderRows(onLaunch = {}))
    }
}

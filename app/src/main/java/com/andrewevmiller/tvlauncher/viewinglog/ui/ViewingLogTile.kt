package com.andrewevmiller.tvlauncher.viewinglog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.fieldTile

/**
 * The "Viewing log" tile in Home's Tools row, beside the sleep timer tile: the same size and style. OK opens the
 * log. [focusRequest] counts up each time the log closes, and focus lands back on this tile on each change, so Back
 * returns to the tile the user came from.
 */
@Composable
fun ViewingLogTile(
    onClick: () -> Unit,
    focusRequest: Int,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val requester = remember { FocusRequester() }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            // After the frame that lays Home out again, so the tile is attached.
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    Row(
        modifier
            .size(TILE_WIDTH, 64.dp)
            .focusRequester(requester)
            .fieldTile(onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(FieldIcons.Log, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(colors.muted))
        Text(
            "Viewing log",
            style = Field.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 16.sp),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val TILE_WIDTH = 168.dp

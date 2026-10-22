package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One timeline entry: an app and when this launcher last opened it (epoch millis). */
data class RecentEntry(val app: LauncherApp, val at: Long)

private val dateFormat = DateTimeFormatter.ofPattern("MM-dd")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Collapsed by default to a single "Show recent" chip; expanding lists the timeline beside it. */
@Composable
fun RecentRow(
    entries: List<RecentEntry>,
    open: Boolean,
    onToggle: () -> Unit,
    onLaunch: (LauncherApp) -> Unit,
) {
    val colors = Field.colors
    Row(
        Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (open) "Hide" else "Show recent",
            style = Field.type.chip,
            color = colors.muted,
            modifier = Modifier
                .fieldTile(onToggle, FieldShapes.chip)
                .background(colors.bgSoft, FieldShapes.chip)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
        if (open) entries.forEach { RecentTile(it) { onLaunch(it.app) } }
    }
}

@Composable
private fun RecentTile(entry: RecentEntry, onClick: () -> Unit) {
    val colors = Field.colors
    val shape = RoundedCornerShape(4.dp)
    var focused by remember { mutableStateOf(false) }
    val time = Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault())
    Row(
        Modifier
            .width(236.dp)
            .onFocusChanged { focused = it.hasFocus }
            .fieldTile(onClick, shape)
            .background(if (focused) colors.bgSoft else androidx.compose.ui.graphics.Color.Transparent, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(50.dp)) {
            val style = Field.type.recentTime
            Text(dateFormat.format(time), style = style, color = colors.muted)
            Text(timeFormat.format(time), style = style, color = colors.muted)
        }
        val iconWidth = if (entry.app.artworkIsBanner) 39.dp else 22.dp
        entry.app.artwork?.let { Image(it, null, Modifier.size(iconWidth, 22.dp)) } ?: Spacer(Modifier.size(iconWidth, 22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.app.label,
                style = Field.type.recentName,
                color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.app.category.name.lowercase().replaceFirstChar { it.uppercase() },
                style = Field.type.recentMeta,
                color = colors.muted,
            )
        }
    }
}

package com.andrewevmiller.tvlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcon

/**
 * One line of the Recent timeline. [dateLabel] and [timeLabel] are ready-to-show strings (e.g. "10-21" and
 * "18:05"); [icon] is a `FieldIcons.*` glyph; [title] is the app or show, [subtitle] the detail under it.
 */
data class TimelineItem(
    val key: String,
    val dateLabel: String,
    val timeLabel: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

private val EntryWidth = 280.dp
private val EntryDateWidth = 56.dp

/**
 * A timeline entry (the mockup's `.entry`): a mono date/time column, a line icon, the title in Geist and a
 * muted mono subtitle. Standard `fieldTile` focus; while focused the entry also lifts onto `bgSoft` and the
 * icon turns accent (the focused element's own emphasis). [onClick] runs on OK, after `FieldSound.select()`
 * unless [playSelect] is false (pass false when the click handler plays the app's launch motif instead).
 */
@Composable
fun TimelineEntry(
    item: TimelineItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playSelect: Boolean = true,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .width(EntryWidth)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .fieldTile(onClick = {
                if (playSelect) sound.select()
                onClick()
            })
            .background(if (focused) colors.bgSoft else Color.Transparent, FieldShapes.tile)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(EntryDateWidth)) {
            Text(item.dateLabel, style = Field.type.caption, color = colors.muted, maxLines = 1)
            Text(item.timeLabel, style = Field.type.caption, color = colors.muted, maxLines = 1)
        }
        FieldIcon(
            icon = item.icon,
            modifier = Modifier.padding(top = 3.dp),
            tint = if (focused) colors.accent else colors.muted,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = Field.type.bodyStrong,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = Field.type.caption,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The collapsible Recent row. State is hoisted: the caller owns [expanded] (collapsed by default, and it
 * collapses again when Home is shown) and flips it in [onToggle].
 *
 * Collapsed, the row is a single chip, "Show recent"; the hidden count belongs in the section label the
 * caller draws, see [recentSectionLabel]. Expanded, the chip reads "Hide" and the entries follow it in
 * the same lazy row. The chip plays `FieldSound.select()`; entries call [onOpen] after select unless
 * [playSelect] is false. LEFT/RIGHT at either end of the row plays `FieldSound.edge()`.
 */
@Composable
fun RecentRow(
    items: List<TimelineItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (TimelineItem) -> Unit,
    modifier: Modifier = Modifier,
    playSelect: Boolean = true,
) {
    val sound = LocalFieldSound.current
    val count = recentFocusableCount(expanded, items.size)
    LazyRow(
        modifier = modifier.focusBleed(),
        horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap),
        contentPadding = PaddingValues(FocusBleed),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = RecentToggleKey) {
            FieldChip(
                label = recentToggleLabel(expanded),
                selected = expanded,
                onClick = {
                    sound.select()
                    onToggle()
                },
                modifier = Modifier.rowEdgeKeys(0, count) { sound.edge() },
            )
        }
        if (expanded) {
            itemsIndexed(items, key = { _, entry -> entry.key }) { index, entry ->
                TimelineEntry(
                    item = entry,
                    onClick = { onOpen(entry) },
                    modifier = Modifier.rowEdgeKeys(index + 1, count) { sound.edge() },
                    playSelect = playSelect,
                )
            }
        }
    }
}

private const val RecentToggleKey = "__recent_toggle__"

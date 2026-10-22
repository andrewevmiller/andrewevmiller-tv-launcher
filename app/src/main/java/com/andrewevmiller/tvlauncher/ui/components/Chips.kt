package com.andrewevmiller.tvlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile

/** One filter chip: the value handed to `onSelect` and the label shown. */
data class ChipOption<T>(val value: T, val label: String)

/**
 * A row of pill chips (the mockup's `.chip`), e.g. All / Video / Music / Games / Tools.
 *
 * The selected chip is the one filled with `panel` and lettered in `ink`; the others sit on `bgSoft` in
 * `muted`. The accent is only the standard focus border (`fieldTile`), never a fill. OK on a chip plays
 * `FieldSound.select()` and calls [onSelect]; LEFT on the first chip and RIGHT on the last play
 * `FieldSound.edge()` and are consumed. The list scrolls horizontally with room for the focus effect.
 */
@Composable
fun <T> ChipsRow(
    options: List<ChipOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sound = LocalFieldSound.current
    LazyRow(
        modifier = modifier.focusBleed(),
        horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap),
        contentPadding = PaddingValues(FocusBleed),
    ) {
        itemsIndexed(options) { index, option ->
            FieldChip(
                label = option.label,
                selected = option.value == selected,
                onClick = {
                    sound.select()
                    onSelect(option.value)
                },
                modifier = Modifier.rowEdgeKeys(index, options.size) { sound.edge() },
            )
        }
    }
}

/**
 * The pill itself, shared by [ChipsRow] and the Recent row's toggle. Pass the row-end key handling and any
 * focus requester in [modifier]; it comes first in the chain so it sees the key events of the focused chip.
 */
@Composable
internal fun FieldChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    Box(
        modifier
            .fieldTile(onClick = onClick, shape = FieldShapes.chip)
            .background(if (selected) colors.panel else colors.bgSoft, FieldShapes.chip)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = Field.type.label,
            color = if (selected) colors.ink else colors.muted,
            maxLines = 1,
        )
    }
}

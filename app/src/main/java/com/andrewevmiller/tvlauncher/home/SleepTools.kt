package com.andrewevmiller.tvlauncher.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sleep.SleepPresets
import com.andrewevmiller.tvlauncher.sleep.SleepTimerController
import com.andrewevmiller.tvlauncher.sleep.SleepTimerState
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile
import com.andrewevmiller.tvlauncher.ui.components.rowEdgeKeys
import kotlinx.coroutines.delay

/** Plain holder: changing it never recomposes. */
private class WasOpen(var value: Boolean)

/**
 * The Home "Tools" row: the Sleep timer tile, and under it (when [open]) the picker. [open] lives in the caller
 * so Back can close the picker first. OK on the tile toggles it and focus lands on the user's default length
 * ([defaultMinutes]); OK on a chip acts, plays the select sound, collapses the picker and returns focus to the
 * tile. The picker also closes by itself when the timer runs out. [now] is the wall clock (tests move it).
 * [trailing] is any other tile on the same row; the picker opens under the sleep tile only.
 */
@Composable
fun SleepTools(
    controller: SleepTimerController,
    defaultMinutes: Int,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
    /** Another tool tile, drawn to the right of the sleep tile in the same row (the Viewing log). Null draws none. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val sound = LocalFieldSound.current
    val state by controller.state.collectAsState()
    val running = SleepToolsLogic.isRunning(state)
    val options = SleepToolsLogic.options(running)
    val tileRequester = remember { FocusRequester() }
    val chipRequesters = remember { List(16) { FocusRequester() } }
    val wasOpen = remember { WasOpen(false) }
    val latestOpen by rememberUpdatedState(open)
    val latestOptions by rememberUpdatedState(options)
    val latestDefault by rememberUpdatedState(defaultMinutes)

    // Focus follows the picker: onto the default length when it opens, back onto the tile when it closes.
    LaunchedEffect(open) {
        val was = wasOpen.value
        wasOpen.value = open
        if (open) {
            withFrameNanos { }
            val index = SleepToolsLogic.defaultFocusIndex(latestOptions, latestDefault)
            runCatching { chipRequesters[index].requestFocus() }
        } else if (was) {
            withFrameNanos { }
            runCatching { tileRequester.requestFocus() }
        }
    }
    // The timer ran out (or was stopped elsewhere) while the picker was open: close it.
    LaunchedEffect(running) {
        if (!running && latestOpen) onOpenChange(false)
    }

    Column(modifier) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SleepTile(
                state = state,
                now = now,
                onClick = {
                    sound.select()
                    onOpenChange(!open)
                },
                modifier = Modifier.focusRequester(tileRequester),
            )
            trailing?.invoke()
        }
        if (open) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sleep after · min",
                    style = Field.type.caption.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.04.em),
                    color = Field.colors.muted,
                    modifier = Modifier.padding(end = 4.dp),
                )
                options.forEachIndexed { index, option ->
                    SleepChip(
                        label = option.label,
                        selected = SleepToolsLogic.isSelected(option, state),
                        onClick = {
                            // Read the timer as it is now: it may have run out while the picker was open.
                            when (val action = SleepToolsLogic.actionFor(option, controller.state.value)) {
                                is SleepAction.Start -> controller.start(action.minutes)
                                SleepAction.Extend -> controller.extend(SleepPresets.EXTEND_MINUTES)
                                SleepAction.Cancel -> controller.cancel()
                                SleepAction.None -> Unit
                            }
                            sound.select()
                            onOpenChange(false)
                        },
                        // The row ends are edges: the thud, rather than focus leaping to a tile far up and to the right.
                        modifier = Modifier
                            .rowEdgeKeys(index, options.size) { sound.edge() }
                            .focusRequester(chipRequesters[index]),
                    )
                }
            }
        }
    }
}

/** Same size and style as the other system tiles, a little wider so the countdown fits beside the moon. */
@Composable
private fun SleepTile(state: SleepTimerState, now: () -> Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Field.colors
    // Only this tile reads the ticking time, so the rest of Home is not recomposed as the caption counts down.
    var tick by remember { mutableStateOf(now()) }
    LaunchedEffect(state) {
        while (state is SleepTimerState.Running) {
            tick = now()
            delay(SleepToolsLogic.nextTickDelayMillis(state.remainingMillis(tick)))
        }
        tick = now()
    }
    Row(
        modifier.size(SLEEP_TILE_WIDTH, 64.dp).fieldTile(onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(FieldIcons.Moon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(colors.muted))
        Column {
            Text(
                "Sleep timer",
                style = Field.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = colors.ink, maxLines = 1,
            )
            Text(
                SleepToolsLogic.caption(state, tick),
                style = Field.type.caption.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.04.em),
                color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SLEEP_TILE_WIDTH = 168.dp

/** A pill like Home's category chips: the selected one is lettered in ink, the rest in muted. Accent is focus only. */
@Composable
internal fun SleepChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Field.colors
    Text(
        label,
        style = Field.type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
        color = if (selected) colors.ink else colors.muted,
        modifier = modifier
            .fieldTile(onClick, FieldShapes.chip)
            .background(colors.bgSoft, FieldShapes.chip)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

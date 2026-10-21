package com.andrewevmiller.tvlauncher.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcon
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcons
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/**
 * Title on the left, a lab-notebook timestamp on the right: `2026-10-21 · 19:42:07 PDT` (see [ClockFormat]).
 * With [onOpenSettings] a small LAUNCHER SETTINGS chip sits left of the timestamp (the Menu key opens it too).
 */
@Composable
fun ClockHeader(title: String = "Home", onOpenSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Read the zone fresh each tick so a time-zone change on the TV shows up at once.
            val tick = ZonedDateTime.now()
            now = tick
            delay(ClockFormat.millisUntilNextSecond(tick.nano))
        }
    }
    val text = remember(now, is24Hour) { ClockFormat.format(now, is24Hour) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = Field.type.title, color = Field.colors.ink)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (onOpenSettings != null) SettingsChip(onOpenSettings)
            Text(text, style = Field.type.label, color = Field.colors.muted)
        }
    }
}

@Composable
private fun SettingsChip(onClick: () -> Unit) {
    val sound = LocalFieldSound.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .fieldTile(onClick = { sound.select(); onClick() }, shape = FieldShapes.chip)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FieldIcon(
            FieldIcons.Gear,
            size = 18.dp,
            tint = if (focused) Field.colors.accent else Field.colors.muted,
        )
        // "Launcher": the Inputs & system row has its own "Settings" tile, which opens the TV's settings.
        Text("LAUNCHER SETTINGS", style = Field.type.label, color = Field.colors.muted)
    }
}

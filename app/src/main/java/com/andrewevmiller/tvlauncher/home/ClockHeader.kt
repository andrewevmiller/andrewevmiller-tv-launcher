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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Title on the left, a lab-notebook timestamp on the right: `2026-10-21 · 19:42`.
 * With [onOpenSettings] a small SETTINGS chip sits left of the timestamp (the Menu key opens it too).
 */
@Composable
fun ClockHeader(title: String = "Home", onOpenSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(context)) "yyyy-MM-dd · HH:mm" else "yyyy-MM-dd · h:mm a"
    val formatter = remember(pattern) { DateTimeFormatter.ofPattern(pattern) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // Wake at the top of the next minute.
            delay((60 - now.second) * 1000L - now.nano / 1_000_000L)
        }
    }
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
            Text(now.format(formatter), style = Field.type.label, color = Field.colors.muted)
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
        Text("SETTINGS", style = Field.type.label, color = Field.colors.muted)
    }
}

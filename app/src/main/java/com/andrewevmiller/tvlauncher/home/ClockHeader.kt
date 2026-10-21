package com.andrewevmiller.tvlauncher.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.theme.Field
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Title on the left, a lab-notebook timestamp on the right: `2026-10-21 · 19:42`. */
@Composable
fun ClockHeader(title: String = "Home") {
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
        Text(title, style = Field.type.title.copy(fontSize = 30.sp, lineHeight = 32.sp), color = Field.colors.ink)
        Text(now.format(formatter), style = Field.type.label.copy(fontSize = 13.sp), color = Field.colors.muted)
    }
}

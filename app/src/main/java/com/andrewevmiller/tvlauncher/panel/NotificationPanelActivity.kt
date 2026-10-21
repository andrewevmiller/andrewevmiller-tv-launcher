package com.andrewevmiller.tvlauncher.panel

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.NowPlayingListener
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.fieldTile
import kotlinx.coroutines.android.awaitFrame
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One row in the panel. */
data class PanelItem(val key: String, val app: String, val title: String, val text: String?, val at: Long)

/**
 * The TV's notifications panel. The remote's Settings button makes the system ask for
 * `android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL`; the Google TV launcher used to answer and
 * is disabled on this TV, so the press did nothing. This answers it: a panel down the right-hand
 * side with a shortcut to Settings, then the current notifications. Pressing the button again
 * closes it.
 */
class NotificationPanelActivity : ComponentActivity() {
    private val items = mutableStateListOf<PanelItem>()
    private var notifications: Map<String, StatusBarNotification> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reload()
        setContent {
            FieldTheme {
                Panel(
                    items = items,
                    connected = NowPlayingListener.instance != null,
                    onSettings = { go(Intent(Settings.ACTION_SETTINGS)) },
                    onOpen = { open(it) },
                    onClear = { clearAll() },
                )
            }
        }
    }

    /** The same button again while the panel is up closes it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
    }

    private fun reload() {
        val found = NowPlayingListener.instance?.activeNotifications.orEmpty()
            .filter { !it.isOngoing && it.packageName != packageName }
            .sortedByDescending { it.postTime }
        notifications = found.associateBy { it.key }
        items.clear()
        found.forEach { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (title == null && text == null) return@forEach
            items += PanelItem(sbn.key, appLabel(sbn.packageName), title ?: text.orEmpty(), if (title != null) text else null, sbn.postTime)
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun go(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private fun open(item: PanelItem) {
        runCatching { notifications[item.key]?.notification?.contentIntent?.send() }
        NowPlayingListener.instance?.cancelNotification(item.key)
        finish()
    }

    private fun clearAll() {
        NowPlayingListener.instance?.cancelAllNotifications()
        items.clear()
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun Panel(
    items: List<PanelItem>,
    connected: Boolean,
    onSettings: () -> Unit,
    onOpen: (PanelItem) -> Unit,
    onClear: () -> Unit,
) {
    val colors = Field.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { first.requestFocus() }
    }
    // The dimmed screen on the left is just the app underneath; the panel is the right 380dp.
    Row(Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.55f))) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.line))
        Column(Modifier.fillMaxHeight().width(380.dp).background(colors.panel).padding(horizontal = 24.dp, vertical = 27.dp)) {
            Text(
                "NOTIFICATIONS${if (items.isNotEmpty()) " · ${items.size}" else ""}",
                style = Field.type.label.copy(fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
                color = colors.muted,
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                item("settings") {
                    Row(
                        Modifier.fillMaxWidth().focusRequester(first).fieldTile(onSettings, FieldShapes.tile)
                            .background(colors.bgSoft, FieldShapes.tile).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Open Settings", style = Field.type.bodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp), color = colors.ink)
                    }
                }
                if (items.isEmpty()) {
                    item("empty") {
                        Text(
                            if (connected) "No notifications." else "Notification access is off. Run ./tools/now-playing.ps1 enable.",
                            style = Field.type.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
                            color = colors.muted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                items(items, key = { it.key }) { item ->
                    Column(
                        Modifier.fillMaxWidth().fieldTile({ onOpen(item) }, FieldShapes.tile)
                            .background(colors.bgSoft, FieldShapes.tile).padding(14.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                item.app.uppercase(),
                                style = Field.type.caption.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.06.em),
                                color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(
                                timeFormat.format(Instant.ofEpochMilli(item.at).atZone(ZoneId.systemDefault())),
                                style = Field.type.caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
                                color = colors.muted,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.title,
                            style = Field.type.bodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp),
                            color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        item.text?.let {
                            Text(
                                it,
                                style = Field.type.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                                color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (items.isNotEmpty()) {
                    item("clear") {
                        Text(
                            "Clear all",
                            style = Field.type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
                            color = colors.muted,
                            modifier = Modifier.fieldTile(onClear, FieldShapes.chip).background(colors.bgSoft, FieldShapes.chip)
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

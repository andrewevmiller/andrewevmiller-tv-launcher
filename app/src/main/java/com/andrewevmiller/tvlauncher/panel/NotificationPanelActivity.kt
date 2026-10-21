package com.andrewevmiller.tvlauncher.panel

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.NowPlayingListener
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.fieldTile
import kotlinx.coroutines.android.awaitFrame
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One row in the notifications list. */
data class PanelItem(val key: String, val app: String, val title: String, val text: String?, val at: Long)

/** One quick-settings tile: opens a TV settings screen, with an optional line of live status. */
data class QuickTile(val label: String, val status: String?, val icon: ImageVector, val intent: Intent)

/**
 * The TV's notifications panel, opened by the remote's Settings button. The system asks for
 * `android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL`; the Google TV launcher used to answer and
 * is disabled on this TV, so the launcher answers it itself (see RemoteKeyService). Down the
 * right-hand side: a volume bar, quick-settings tiles that open the TV's own screens, then the
 * current notifications. Pressing the button again closes it.
 */
class NotificationPanelActivity : ComponentActivity() {
    private val items = mutableStateListOf<PanelItem>()
    private var notifications: Map<String, StatusBarNotification> = emptyMap()
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reload()
        setContent {
            FieldTheme {
                var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
                var muted by remember { mutableStateOf(audio.isStreamMute(AudioManager.STREAM_MUSIC)) }
                Panel(
                    tiles = quickTiles(),
                    volume = volume,
                    maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    muted = muted,
                    onVolume = { up ->
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    },
                    onMute = {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    },
                    items = items,
                    connected = NowPlayingListener.instance != null,
                    onTile = { go(it.intent) },
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

    private fun quickTiles(): List<QuickTile> {
        val online = run {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi connected"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
        }
        val bluetooth = if (Settings.Global.getInt(contentResolver, "bluetooth_on", 0) != 0) "On" else "Off"
        val bluetoothScreen = Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"))
        return listOf(
            QuickTile("Wi-Fi", online, FieldIcons.Wifi, Intent(Settings.ACTION_WIFI_SETTINGS)),
            QuickTile("Bluetooth", bluetooth, FieldIcons.Bluetooth, bluetoothScreen),
            QuickTile("Sound", null, FieldIcons.Speaker, Intent(Settings.ACTION_SOUND_SETTINGS)),
            QuickTile("Picture", null, FieldIcons.Monitor, Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            QuickTile("Date & time", null, FieldIcons.Clock, Intent(Settings.ACTION_DATE_SETTINGS)),
            QuickTile("Keyboard", null, FieldIcons.Keyboard, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),
            QuickTile("Apps", null, FieldIcons.Grid, Intent(Settings.ACTION_APPLICATION_SETTINGS)),
            QuickTile("Accessibility", null, FieldIcons.Access, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            QuickTile("All settings", null, FieldIcons.Gear, Intent(Settings.ACTION_SETTINGS)),
        )
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = Field.type.label.copy(fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
        color = Field.colors.muted,
    )
}

@Composable
private fun Panel(
    tiles: List<QuickTile>,
    volume: Int,
    maxVolume: Int,
    muted: Boolean,
    onVolume: (up: Boolean) -> Unit,
    onMute: () -> Unit,
    items: List<PanelItem>,
    connected: Boolean,
    onTile: (QuickTile) -> Unit,
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
            SectionLabel("QUICK SETTINGS")
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                item("volume") { VolumeRow(volume, maxVolume, muted, onVolume, onMute) }
                items(tiles.chunked(2), key = { row -> row.first().label }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { tile ->
                            Tile(tile, Modifier.weight(1f).then(if (tile === tiles.first()) Modifier.focusRequester(first) else Modifier)) { onTile(tile) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item("notifications-head") {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("NOTIFICATIONS${if (items.isNotEmpty()) " · ${items.size}" else ""}")
                }
                if (items.isEmpty()) {
                    item("empty") {
                        Text(
                            if (connected) "No notifications." else "Notification access is off. Run ./tools/now-playing.ps1 enable.",
                            style = Field.type.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
                            color = colors.muted,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
                items(items, key = { it.key }) { item -> NotificationRow(item) { onOpen(item) } }
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

/** Volume: Left and Right change it, OK mutes. */
@Composable
private fun VolumeRow(volume: Int, maxVolume: Int, muted: Boolean, onVolume: (Boolean) -> Unit, onMute: () -> Unit) {
    val colors = Field.colors
    Column(
        Modifier.fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onVolume(false); true }
                    Key.DirectionRight -> { onVolume(true); true }
                    else -> false
                }
            }
            .fieldTile(onMute, FieldShapes.tile)
            .background(colors.bgSoft, FieldShapes.tile)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(if (muted) FieldIcons.SpeakerMuted else FieldIcons.Speaker, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(colors.muted))
            Spacer(Modifier.width(10.dp))
            Text("Volume", style = Field.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 16.sp), color = colors.ink, modifier = Modifier.weight(1f))
            Text(
                if (muted) "MUTED" else "$volume",
                style = Field.type.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em),
                color = colors.muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).background(colors.line)) {
            val fraction = if (maxVolume > 0 && !muted) volume.toFloat() / maxVolume else 0f
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(colors.muted))
        }
    }
}

@Composable
private fun Tile(tile: QuickTile, modifier: Modifier, onClick: () -> Unit) {
    val colors = Field.colors
    Row(
        modifier.fieldTile(onClick, FieldShapes.tile).background(colors.bgSoft, FieldShapes.tile).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(tile.icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(colors.muted))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(tile.label, style = Field.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 16.sp), color = colors.ink, maxLines = 1)
            tile.status?.let {
                Text(
                    it.uppercase(),
                    style = Field.type.caption.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.05.em),
                    color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NotificationRow(item: PanelItem, onClick: () -> Unit) {
    val colors = Field.colors
    Column(
        Modifier.fillMaxWidth().fieldTile(onClick, FieldShapes.tile).background(colors.bgSoft, FieldShapes.tile).padding(14.dp),
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

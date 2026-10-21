package com.andrewevmiller.tvlauncher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.Feature
import com.andrewevmiller.tvlauncher.home.RecentEntry
import com.andrewevmiller.tvlauncher.home.data.LaunchHistory
import com.andrewevmiller.tvlauncher.home.data.LaunchRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.home.orderPinned
import com.andrewevmiller.tvlauncher.home.data.InstalledApps
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import androidx.compose.runtime.CompositionLocalProvider
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var sound: FieldSound
    private lateinit var history: LaunchHistory
    private var records by mutableStateOf<List<LaunchRecord>>(emptyList())
    private var recentOpen by mutableStateOf(false)
    private lateinit var layoutPrefs: android.content.SharedPreferences
    private var order by mutableStateOf<List<String>>(emptyList())
    private var editing by mutableStateOf<String?>(null)
    private var apps by mutableStateOf<List<LauncherApp>>(emptyList())
    private var chip by mutableStateOf(Chip.ALL)
    private var drawer by mutableStateOf(false)
    private var systemTiles by mutableStateOf<List<SystemTile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher is the bottom of the stack; Back only leaves the drawer.
        onBackPressedDispatcher.addCallback(this) {
            if (editing != null) {
                editing = null
                sound.select()
            } else if (drawer) {
                drawer = false
                chip = Chip.ALL
            }
        }
        sound = FieldSound(this)
        history = LaunchHistory(this)
        layoutPrefs = getSharedPreferences("layout", MODE_PRIVATE)
        order = (layoutPrefs.getString("order", "") ?: "").split(';').filter { it.isNotEmpty() }
        records = history.entries()
        systemTiles = buildSystemTiles()
        setContent {
            FieldTheme {
              CompositionLocalProvider(LocalFieldSound provides sound) {
                HomeScreen(
                    allApps = apps,
                    systemTiles = systemTiles,
                    chip = chip,
                    drawer = drawer,
                    onChip = {
                        sound.select()
                        chip = it
                    },
                    onOpenDrawer = {
                        sound.select()
                        drawer = true
                        chip = Chip.ALL
                    },
                    feature = featureFor(),
                    recent = recentFor(),
                    recentOpen = recentOpen,
                    order = order,
                    editingPkg = editing,
                    onReorderStart = {
                        editing = it.component.packageName
                        sound.select()
                    },
                    onMove = { app, delta -> move(app, delta) },
                    onEditEnd = {
                        editing = null
                        sound.select()
                    },
                    onToggleRecent = {
                        sound.select()
                        recentOpen = !recentOpen
                    },
                    onLaunch = {
                        sound.launch(it)
                        records = history.record(it.component.packageName)
                        InstalledApps.launch(this, it)
                    },
                )
              }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home always returns to the top of Home.
        drawer = false
        chip = Chip.ALL
        recentOpen = false
        editing = null
    }

    override fun onResume() {
        super.onResume()
        // Reload on every return so installs and uninstalls show up.
        thread { apps = InstalledApps.load(this) }
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }

    /** Moves [app] by [delta] places in the Home order and saves it; the edges answer with the thud. */
    private fun move(app: LauncherApp, delta: Int) {
        val current = orderPinned(apps, order).map { it.component.packageName }.toMutableList()
        val from = current.indexOf(app.component.packageName)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.size - 1)
        if (to == from) return sound.edge()
        current.add(to, current.removeAt(from))
        order = current
        layoutPrefs.edit().putString("order", current.joinToString(";")).apply()
        sound.tick()
    }

    private fun recentFor(): List<RecentEntry> {
        val byPkg = apps.associateBy { it.component.packageName }
        return records.mapNotNull { r -> byPkg[r.packageName]?.let { RecentEntry(it, r.at) } }.take(5)
    }

    /** The app opened most recently; before anything has been opened, the first pinned app. */
    private fun featureFor(): Feature? {
        val recent = recentFor().firstOrNull()
        if (recent != null) {
            val at = Instant.ofEpochMilli(recent.at).atZone(ZoneId.systemDefault())
            return Feature(recent.app, DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm").format(at))
        }
        return apps.firstOrNull { it.pinned }?.let { Feature(it, null) }
    }

    private fun open(intent: Intent) {
        sound.select()
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun buildSystemTiles(): List<SystemTile> {
        val inputs = runCatching {
            (getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager).tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI }
        }.getOrDefault(emptyList())
        val hdmi = inputs.map { input ->
            val label = input.loadCustomLabel(this)?.toString() ?: input.loadLabel(this).toString()
            SystemTile(label, FieldIcons.Port) {
                open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)))
            }
        }
        return hdmi + listOf(
            SystemTile("Wi-Fi", FieldIcons.Wifi) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            SystemTile("Search", FieldIcons.Search) { open(Intent("android.search.action.GLOBAL_SEARCH")) },
            SystemTile("Settings", FieldIcons.Gear) { open(Intent(Settings.ACTION_SETTINGS)) },
        )
    }
}

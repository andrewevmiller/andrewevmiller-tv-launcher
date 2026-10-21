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
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.lifecycle.lifecycleScope
import com.andrewevmiller.tvlauncher.theme.Geist
import com.andrewevmiller.tvlauncher.theme.PlexMono
import com.andrewevmiller.tvlauncher.theme.PlexSerif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import com.andrewevmiller.tvlauncher.home.MenuAction
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.home.orderPinned
import com.andrewevmiller.tvlauncher.home.data.AppLoader
import com.andrewevmiller.tvlauncher.home.data.InstalledApps
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import androidx.compose.runtime.CompositionLocalProvider
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldTheme

class MainActivity : ComponentActivity() {
    private lateinit var sound: FieldSound
    private lateinit var loader: AppLoader
    private lateinit var history: LaunchHistory
    private var records by mutableStateOf<List<LaunchRecord>>(emptyList())
    private var recentOpen by mutableStateOf(false)
    private lateinit var layoutPrefs: android.content.SharedPreferences
    private var order by mutableStateOf<List<String>>(emptyList())
    private var editing by mutableStateOf<String?>(null)
    private var pinnedSet by mutableStateOf<Set<String>?>(null)
    private var menuApp by mutableStateOf<LauncherApp?>(null)
    private var apps by mutableStateOf<List<LauncherApp>>(emptyList())
    private var chip by mutableStateOf(Chip.ALL)
    private var drawer by mutableStateOf(false)
    private var systemTiles by mutableStateOf<List<SystemTile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher is the bottom of the stack; Back only leaves the drawer.
        onBackPressedDispatcher.addCallback(this) {
            if (menuApp != null) {
                menuApp = null
                sound.back()
            } else if (editing != null) {
                editing = null
                sound.select()
            } else if (drawer) {
                drawer = false
                chip = Chip.ALL
            }
        }
        preloadFonts()
        sound = FieldSound(this)
        loader = AppLoader(this) {
            apps = it
            // Render Home's launch motifs in the background now, so a launch never waits for them.
            sound.prepareMotifs(it)
        }
        history = LaunchHistory(this)
        layoutPrefs = getSharedPreferences("layout", MODE_PRIVATE)
        order = (layoutPrefs.getString("order", "") ?: "").split(';').filter { it.isNotEmpty() }
        // No saved choice yet means nothing is pinned; Home starts empty and the user picks.
        pinnedSet = if (layoutPrefs.contains("pinned")) {
            (layoutPrefs.getString("pinned", "") ?: "").split(';').filter { it.isNotEmpty() }.toSet()
        } else null
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
                    pinnedSet = pinnedSet,
                    menuApp = menuApp,
                    onMenuPick = { pick(it) },
                    onLongPress = {
                        menuApp = it
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

    /** Reads the font files on a background thread now, so the first frame doesn't wait for them on the main thread. */
    private fun preloadFonts() {
        val resolver = createFontFamilyResolver(this)
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                resolver.preload(PlexSerif)
                resolver.preload(Geist)
                resolver.preload(PlexMono)
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
        menuApp = null
    }

    override fun onResume() {
        super.onResume()
        // Cheap when nothing was installed, removed or updated; otherwise rebuilds only what changed.
        loader.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.close()
        sound.release()
    }

    /** Moves [app] by [delta] places in the Home order and saves it; the edges answer with the thud. */
    private fun move(app: LauncherApp, delta: Int) {
        val current = orderPinned(apps, order, pinnedSet).map { it.component.packageName }.toMutableList()
        val from = current.indexOf(app.component.packageName)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.size - 1)
        if (to == from) return sound.edge()
        current.add(to, current.removeAt(from))
        order = current
        layoutPrefs.edit().putString("order", current.joinToString(";")).apply()
        sound.tick()
    }

    private fun isPinned(app: LauncherApp) = pinnedSet?.contains(app.component.packageName) ?: false

    /** Runs the choice from the long-press menu and saves the result on the TV. */
    private fun pick(action: MenuAction) {
        val app = menuApp ?: return
        val pkg = app.component.packageName
        menuApp = null
        when (action) {
            MenuAction.MOVE -> {
                editing = pkg
                sound.select()
            }
            MenuAction.PIN, MenuAction.UNPIN -> {
                val current = apps.filter { isPinned(it) }.map { it.component.packageName }.toSet()
                val nextSet = if (action == MenuAction.PIN) current + pkg else current - pkg
                // A new pin goes to the end of Home; the saved order keeps everyone else where they were.
                val nextOrder = orderPinned(apps, order, current).map { it.component.packageName }.filter { it != pkg } +
                    if (action == MenuAction.PIN) listOf(pkg) else emptyList()
                pinnedSet = nextSet
                order = nextOrder
                layoutPrefs.edit()
                    .putString("pinned", nextSet.joinToString(";"))
                    .putString("order", nextOrder.joinToString(";"))
                    .apply()
                sound.select()
            }
        }
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
        return null
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

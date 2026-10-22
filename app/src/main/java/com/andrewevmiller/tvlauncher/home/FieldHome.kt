package com.andrewevmiller.tvlauncher.home

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.andrewevmiller.tvlauncher.AppContainer
import com.andrewevmiller.tvlauncher.home.data.AppLoader
import com.andrewevmiller.tvlauncher.home.data.HomeLaunchHistory
import com.andrewevmiller.tvlauncher.home.data.InstalledApps
import com.andrewevmiller.tvlauncher.home.data.LaunchRecord
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.home.data.NowPlaying
import com.andrewevmiller.tvlauncher.home.data.NowPlayingMonitor
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val GRID_ROW = 2

/**
 * The live Home screen: [HomeScreen] (the mockup layout, feature band, Recent, pin menu, reorder) plus the
 * state it needs. It holds what `MainActivity` used to hold, and keeps the same SharedPreferences ("layout",
 * "launch_history") so pins, order and recents already on the TV carry over.
 *
 * [FieldRoot] creates a fresh one each time Home is pressed, which is how "Recent collapses, the filter
 * resets and the drawer closes" happens. [ui] only carries the toast message and its timeout.
 */
@Composable
fun FieldHome(
    container: AppContainer,
    ui: HomeUiState,
    settings: LauncherSettings,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sound = LocalFieldSound.current

    val history = remember { HomeLaunchHistory(context) }
    val layoutPrefs = remember { context.getSharedPreferences("layout", Context.MODE_PRIVATE) }

    var apps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    // Shows the saved list at once, then rescans only the apps whose package changed (publishes on the main thread).
    val loader = remember { AppLoader(context) { apps = it } }
    DisposableEffect(loader) { onDispose { loader.close() } }
    var records by remember { mutableStateOf<List<LaunchRecord>>(history.entries()) }
    var order by remember {
        mutableStateOf((layoutPrefs.getString("order", "") ?: "").split(';').filter { it.isNotEmpty() })
    }
    // No saved choice yet means nothing is pinned; Home starts empty and the user picks (#5).
    var pinnedSet by remember {
        mutableStateOf(
            if (layoutPrefs.contains("pinned")) {
                (layoutPrefs.getString("pinned", "") ?: "").split(';').filter { it.isNotEmpty() }.toSet()
            } else {
                null
            },
        )
    }
    var chip by remember { mutableStateOf(Chip.ALL) }
    var drawer by remember { mutableStateOf(false) }
    var recentOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var menuApp by remember { mutableStateOf<LauncherApp?>(null) }

    val open: (Intent) -> Unit = { intent ->
        sound.select()
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val systemTiles = remember { buildSystemTiles(context, open) }

    // What some other app is playing right now (needs notification-listener access; see tools/now-playing.ps1).
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    val nowPlayingMonitor = remember { NowPlayingMonitor(context) { nowPlaying = it } }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { nowPlayingMonitor.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { nowPlayingMonitor.stop() }
    // Each composition makes its own monitor, and each owns a thread: close() stops listening and ends it.
    DisposableEffect(nowPlayingMonitor) { onDispose { nowPlayingMonitor.close() } }

    // Cheap when nothing was installed, removed or updated, so installs and uninstalls still show up on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { loader.refresh() }
    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            delay(4_000)
            ui.message = null
        }
    }
    // A launcher is the bottom of the stack: Back only closes the menu, ends a move, or leaves the drawer.
    BackHandler(enabled = menuApp != null || editing != null || drawer) {
        when {
            menuApp != null -> {
                menuApp = null
                sound.back()
            }
            editing != null -> {
                editing = null
                sound.select()
            }
            else -> {
                drawer = false
                chip = Chip.ALL
            }
        }
    }

    fun isPinned(app: LauncherApp) = pinnedSet?.contains(app.component.packageName) ?: false

    fun recentFor(): List<RecentEntry> {
        val byPkg = apps.associateBy { it.component.packageName }
        return records.mapNotNull { r -> byPkg[r.packageName]?.let { RecentEntry(it, r.at) } }.take(5)
    }

    /** The recent apps the band turns through, newest first, each with when it was last opened. */
    fun featuresFor(): List<Feature> = recentFor().take(4).map { r ->
        val at = Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())
        Feature(r.app, DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm").format(at))
    }

    /** Opens the app that is playing; opening it is how you get at its own controls. */
    fun openPlaying(p: NowPlaying) {
        val intent = context.packageManager.getLeanbackLaunchIntentForPackage(p.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(p.packageName) ?: return
        open(intent)
    }

    /** Moves [app] by [delta] places in the Home order and saves it; the edges answer with the thud. */
    fun move(app: LauncherApp, delta: Int) {
        val current = orderPinned(apps, order, pinnedSet).map { it.component.packageName }.toMutableList()
        val from = current.indexOf(app.component.packageName)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.size - 1)
        if (to == from) return sound.edge()
        current.add(to, current.removeAt(from))
        order = current
        layoutPrefs.edit().putString("order", current.joinToString(";")).apply()
        sound.tick(GRID_ROW)
    }

    /** Runs the choice from the long-press menu and saves the result on the TV. */
    fun pick(action: MenuAction) {
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
        onLaunch = { app ->
            sound.launch(app)
            records = history.record(app.component.packageName)
            InstalledApps.launch(context, app)
        },
        features = featuresFor(),
        nowPlaying = nowPlaying,
        onOpenPlaying = { openPlaying(it) },
        recent = recentFor(),
        recentOpen = recentOpen,
        onToggleRecent = {
            sound.select()
            recentOpen = !recentOpen
        },
        order = order,
        editingPkg = editing,
        onLongPress = {
            menuApp = it
            sound.select()
        },
        onMove = { app, delta -> move(app, delta) },
        onEditEnd = {
            editing = null
            sound.select()
        },
        pinnedSet = pinnedSet,
        menuApp = menuApp,
        onMenuPick = { pick(it) },
        modifier = modifier,
        onOpenSettings = onOpenSettings,
        screensaver = {
            ScreensaverTiles(
                screensaver = container.screensaver,
                settings = container.settings,
                current = settings.screensaver,
                onMessage = { ui.message = it },
            )
        },
        dotAlpha = settings.dotGridAlpha,
    )
}

/** HDMI inputs (the TV's own labels) followed by Wi-Fi, Search and the TV's Settings. */
private fun buildSystemTiles(context: Context, open: (Intent) -> Unit): List<SystemTile> {
    val inputs = runCatching {
        (context.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager).tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI }
    }.getOrDefault(emptyList())
    val hdmi = inputs.map { input ->
        val label = input.loadCustomLabel(context)?.toString() ?: input.loadLabel(context).toString()
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

package com.andrewevmiller.tvlauncher.home

import android.text.format.DateFormat
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andrewevmiller.tvlauncher.HomeDeps
import com.andrewevmiller.tvlauncher.ambient.startPhotosSlideshow
import com.andrewevmiller.tvlauncher.home.apps.AppGridState
import com.andrewevmiller.tvlauncher.home.apps.AppsSection
import com.andrewevmiller.tvlauncher.home.apps.system.SystemTilesRow
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.home.data.LaunchLog
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.Screensaver
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.ui.components.ChipOption
import com.andrewevmiller.tvlauncher.ui.components.ChipsRow
import com.andrewevmiller.tvlauncher.ui.components.FeatureBand
import com.andrewevmiller.tvlauncher.ui.components.GalleryItem
import com.andrewevmiller.tvlauncher.ui.components.GalleryRow
import com.andrewevmiller.tvlauncher.ui.components.RecentRow
import com.andrewevmiller.tvlauncher.ui.components.focusBleed
import com.andrewevmiller.tvlauncher.ui.components.TimelineItem
import com.andrewevmiller.tvlauncher.ui.components.recentSectionLabel
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Everything Home remembers while you are on it. A new instance is made each time Home is pressed, which is
 * how "Recent collapses again, the filter resets and focus returns to the apps" happens.
 */
@Stable
class HomeUiState {
    var category by mutableStateOf<AppCategory?>(null)
    var recentExpanded by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    /** Set when coming back from the drawer, so focus lands on the "All apps" tile instead of the first app. */
    var focusAllApps by mutableStateOf(false)

    val grid = AppGridState()
    val listState = LazyListState()
}

/** The chips under the feature band; also the drawer's header. */
val HomeCategoryChips: List<ChipOption<AppCategory?>> = listOf(
    ChipOption(null, "All"),
    ChipOption(AppCategory.VIDEO, "Video"),
    ChipOption(AppCategory.MUSIC, "Music"),
    ChipOption(AppCategory.GAMES, "Games"),
    ChipOption(AppCategory.TOOLS, "Tools"),
)

private const val PHOTOS_KEY = "photos"
private const val OXYFIELD_KEY = "oxyfield"
private const val NO_ON_DEMAND = "This TV won't start it on demand. It will run when idle."

/**
 * Home: feature band (what you opened last), category chips, the app grid, Recent (your own launch history
 * until the Watch Next spike says more is possible), screensaver tiles and the input/system tiles.
 */
@Composable
fun HomeContent(
    deps: HomeDeps,
    ui: HomeUiState,
    settings: LauncherSettings,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while something else (the crash-fallback notice) should keep focus; Home takes it when this clears. */
    holdFocus: Boolean = false,
) {
    val apps = deps.apps
    val sound = LocalFieldSound.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val favorites by apps.favorites.collectAsStateWithLifecycle<List<LauncherApp>?>(initialValue = null)
    val installed by apps.apps.collectAsStateWithLifecycle<List<LauncherApp>?>(initialValue = null)
    val history by deps.launchHistory.entries.collectAsStateWithLifecycle(initialValue = emptyList())

    val byKey = remember(installed) { installed.orEmpty().associateBy { it.component.flattenToString() } }
    val recentEntries = remember(history, byKey) { history.filter { it.key in byKey }.take(8) }
    val last = recentEntries.firstOrNull()

    val launch: (LauncherApp) -> Unit = { app ->
        sound.launch(app)
        apps.launch(app)
    }

    // Focus the apps once they exist: the first tile, or "All apps" when coming back from the drawer.
    val loaded = favorites != null
    LaunchedEffect(ui, loaded, holdFocus) {
        if (!loaded || holdFocus) return@LaunchedEffect
        repeat(30) {
            val ok = if (ui.focusAllApps) ui.grid.requestAllAppsFocus() else ui.grid.requestFocus(0)
            if (ok) {
                ui.focusAllApps = false
                return@LaunchedEffect
            }
            delay(60)
        }
    }
    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            delay(4_000)
            ui.message = null
        }
    }

    val zone = remember { ZoneId.systemDefault() }
    val is24Hour = DateFormat.is24HourFormat(context)
    val timeline = remember(recentEntries, is24Hour) {
        recentEntries.map { e ->
            TimelineItem(
                key = e.key,
                dateLabel = LaunchLog.dateLabel(e.atMillis, zone),
                timeLabel = LaunchLog.timeLabel(e.atMillis, zone, is24Hour),
                icon = FieldIcons.forCategory(e.category),
                title = e.label,
                subtitle = categoryName(e.category),
            )
        }
    }

    val rows = buildList {
        add(
            HomeRow("chips", null) {
                ChipsRow(options = HomeCategoryChips, selected = ui.category, onSelect = { ui.category = it })
            },
        )
        add(
            HomeRow("apps", "Apps · ${favorites?.size ?: 0} pinned") {
                AppsSection(
                    repository = apps,
                    category = ui.category,
                    onOpenDrawer = onOpenDrawer,
                    state = ui.grid,
                )
            },
        )
        if (timeline.isNotEmpty()) {
            add(
                HomeRow("recent", recentSectionLabel(ui.recentExpanded, timeline.size)) {
                    RecentRow(
                        items = timeline,
                        expanded = ui.recentExpanded,
                        onToggle = { ui.recentExpanded = !ui.recentExpanded },
                        onOpen = { item -> byKey[item.key]?.let(launch) },
                        playSelect = false,
                    )
                },
            )
        }
        add(
            HomeRow("screensaver", "Screensaver · start one now") {
                GalleryRow(
                    items = listOf(
                        GalleryItem(PHOTOS_KEY, "Google Photos · slideshow"),
                        GalleryItem(OXYFIELD_KEY, "Oxyfield · dot field"),
                    ),
                    playSelect = false,
                    onSelect = { item ->
                        sound.sleep()
                        when (item.key) {
                            PHOTOS_KEY -> startPhotosSlideshow(context, deps.screensaver, settings.screensaver) {
                                ui.message = it
                            }
                            OXYFIELD_KEY -> {
                                if (deps.screensaver.canSwitch) {
                                    deps.screensaver.apply(Screensaver.OXYFIELD)
                                    scope.launch { deps.settings.update { it.copy(screensaver = Screensaver.OXYFIELD) } }
                                }
                                if (!deps.screensaver.startNow()) ui.message = NO_ON_DEMAND
                            }
                        }
                    },
                )
            },
        )
        add(HomeRow("system", "Inputs & system") { SystemTilesRow(Modifier.focusBleed()) })
    }

    HomeScreen(
        rows = rows,
        modifier = modifier,
        dotAlpha = settings.dotGridAlpha,
        listState = ui.listState,
        header = { ClockHeader(onOpenSettings = onOpenSettings) },
        feature = {
            FeatureBand(
                kicker = if (last != null) {
                    "Last opened · ${LaunchLog.dateLabel(last.atMillis, zone)} ${LaunchLog.timeLabel(last.atMillis, zone, is24Hour)}"
                } else {
                    "Welcome"
                },
                title = last?.label ?: "Field Launcher",
                body = if (last != null) "Pick up where you left off." else "Your apps, inputs and screensaver in one place.",
                ctaLabel = if (last != null) "Open →" else "Browse apps →",
                onCta = {
                    val app = last?.let { byKey[it.key] }
                    if (app != null) launch(app) else { sound.select(); onOpenDrawer() }
                },
                bleed = FieldDimens.safeHorizontal,
                playSelect = false,
            )
        },
    )
}

private fun categoryName(category: AppCategory): String = when (category) {
    AppCategory.VIDEO -> "Video"
    AppCategory.MUSIC -> "Music"
    AppCategory.GAMES -> "Games"
    AppCategory.TOOLS -> "Tools"
    AppCategory.OTHER -> "App"
}

package com.andrewevmiller.tvlauncher.home.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.andrewevmiller.tvlauncher.home.data.hide as layoutHide
import com.andrewevmiller.tvlauncher.home.data.move as layoutMove
import com.andrewevmiller.tvlauncher.home.data.pin as layoutPin
import com.andrewevmiller.tvlauncher.home.data.unhide as layoutUnhide
import com.andrewevmiller.tvlauncher.home.data.unpin as layoutUnpin

private const val TAG = "AppsRepository"

/** How many first-run favorites are pinned. Two full rows of the 4-column grid. */
private const val SEED_COUNT = 8

/** Artwork is loaded this many apps at a time; the UI is refreshed once per batch, not once per app. */
private const val ARTWORK_BATCH = 8

/** How long the shared pipelines stay alive after the last collector leaves. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * [AppsRepository] backed by PackageManager ([PackageAppSource]), rendered artwork ([ArtworkLoader]) and a
 * [LayoutStore]. All list logic is in the pure functions of `AppListLogic.kt`; this class is the glue.
 *
 * Starts work as soon as it is constructed, in [scope]: it watches installed packages, loads artwork
 * (pinned apps first) and, on the first run only (`layout.seeded == false`), pins default favorites.
 */
class AppsRepositoryImpl(
    context: Context,
    private val store: LayoutStore,
    private val scope: CoroutineScope,
) : AppsRepository {
    private val appContext: Context = context.applicationContext
    private val source = PackageAppSource(appContext)
    private val artwork = ArtworkLoader(appContext.packageManager)

    /** Bumped each time a batch of artwork finishes loading, so [arranged] is recomputed with it. */
    private val artworkVersion = MutableStateFlow(0)

    /** The installed apps. One shared PackageManager pipeline for the repository's whole life. */
    private val installed: Flow<List<RawApp>> = source.apps
        .catch { Log.w(TAG, "Watching installed apps failed", it) }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private val arranged: Flow<Arranged<LauncherApp>> =
        combine(installed, store.layout, artworkVersion) { raw, layout, _ ->
            arrange(raw, { it.key }, { it.label }, layout).mapItems { it.toLauncherApp() }
        }
            .flowOn(Dispatchers.Default)
            .catch { Log.w(TAG, "Arranging apps failed", it) }
            .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    override val apps: Flow<List<LauncherApp>> = arranged.map { it.all }.distinctUntilChanged()
    override val favorites: Flow<List<LauncherApp>> = arranged.map { it.favorites }.distinctUntilChanged()
    override val hidden: Flow<List<LauncherApp>> = arranged.map { it.hidden }.distinctUntilChanged()

    init {
        // First run: pin defaults once there is something to pin. seedDefaults is a no-op when layout.seeded
        // is already true, so this is safe on every start and never brings defaults back.
        launchLogged("seed") {
            val list = installed.first { it.isNotEmpty() }
            val order = seedOrder(list, { it.key }, { it.label }, { it.category }, { it.isLeanback })
            store.update { seedDefaults(it, order, SEED_COUNT) }
        }
        launchLogged("artwork") {
            installed.collectLatest { list -> preloadArtwork(list) }
        }
    }

    override suspend fun pin(component: ComponentName) {
        val key = component.flattenToString()
        store.update { layoutPin(it, key) }
    }

    override suspend fun unpin(component: ComponentName) {
        val key = component.flattenToString()
        store.update { layoutUnpin(it, key) }
    }

    override suspend fun move(component: ComponentName, toIndex: Int) {
        val key = component.flattenToString()
        // toIndex counts favorites as the UI shows them, which leaves out pinned apps that aren't installed.
        val installedKeys = installed.first().mapTo(HashSet<String>()) { it.key }
        store.update { layout ->
            layoutMove(layout, key, toIndex) { it in installedKeys && it !in layout.hidden }
        }
    }

    override suspend fun hide(component: ComponentName) {
        val key = component.flattenToString()
        store.update { layoutHide(it, key) }
    }

    override suspend fun unhide(component: ComponentName) {
        val key = component.flattenToString()
        store.update { layoutUnhide(it, key) }
    }

    override fun launch(app: LauncherApp) {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Cannot launch ${app.component.flattenToShortString()}: activity not found", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot launch ${app.component.flattenToShortString()}: not permitted", e)
        }
    }

    private fun RawApp.toLauncherApp(): LauncherApp {
        val art = artwork.peek(this)
        return LauncherApp(
            component = component,
            label = label,
            category = category,
            artwork = art?.image,
            artworkIsBanner = art?.isBanner ?: false,
        )
    }

    /**
     * Loads artwork for every app that doesn't have it yet, a batch at a time, the apps Home shows first:
     * the pinned ones, or on the first run the ones about to be pinned.
     */
    private suspend fun preloadArtwork(list: List<RawApp>) {
        val layout = store.layout.first()
        val firstUp: Set<String> = if (layout.seeded) {
            layout.pinned.toSet()
        } else {
            seedOrder(list, { it.key }, { it.label }, { it.category }, { it.isLeanback }).take(SEED_COUNT).toSet()
        }
        val missing = list
            .sortedBy { if (it.key in firstUp) 0 else 1 }
            .filter { artwork.peek(it) == null }
        for (batch in missing.chunked(ARTWORK_BATCH)) {
            coroutineScope {
                batch.map { app -> async { artwork.load(app) } }.awaitAll()
            }
            artworkVersion.update { it + 1 }
        }
    }

    /** Launches [block] in [scope]; a failure is logged instead of taking the process down. */
    private fun launchLogged(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background task '$what' failed", e)
            }
        }
    }
}

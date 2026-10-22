package com.andrewevmiller.tvlauncher.home.apps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.AppsRepository
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import kotlinx.coroutines.launch

// Glue between AppsRepository and the grid UI. AppsSection and AppDrawerScreen are what HomeScreen and the
// drawer route use. Both apply the optional category chip filter in place (the grid just shows fewer tiles)
// and call repository.launch when an app is selected; the launch sound is played by the tile.

/**
 * The Home "Apps" section: the pinned apps ([AppsRepository.favorites]) as a grid ending in "All apps".
 * Edit mode is on: long-press OK lifts a tile, a drop is stored with `repository.move`, and the strip offers
 * `unpin` and `hide`. With a [category] filter active, a move is mapped back onto the full pinned order.
 *
 * Wire [onExitUp] and [onExitDown] to the neighbouring sections (the chips row above, the next row below);
 * null falls back to the focus manager. Keep one [state] in the caller to enter the grid from above or below
 * ([AppGridState.requestEntry]). The section draws nothing until the first list arrives, so there is no flash
 * of a grid with only the "All apps" cell.
 */
@Composable
fun AppsSection(
    repository: AppsRepository,
    category: AppCategory?,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    state: AppGridState = rememberAppGridState(),
    onExitUp: (() -> Unit)? = null,
    onExitDown: (() -> Unit)? = null,
    focusAllAppsOnEnter: Boolean = false,
) {
    val favorites by repository.favorites.collectAsStateWithLifecycle<List<LauncherApp>?>(initialValue = null)
    val installed by repository.apps.collectAsStateWithLifecycle<List<LauncherApp>?>(initialValue = null)
    val scope = rememberCoroutineScope()

    val full = favorites.orEmpty()
    val shown = remember(favorites, category) { full.filterBy(category) }

    if (favorites != null) {
        AppGridSection(
            apps = shown,
            onLaunch = { repository.launch(it) },
            onOpenDrawer = onOpenDrawer,
            modifier = modifier,
            state = state,
            onExitUp = onExitUp,
            onExitDown = onExitDown,
            onMove = { app, toIndex ->
                // Take the slot of the tile that sits at toIndex now, in the full pinned order.
                val target = fullIndexOfShown(full, shown, toIndex)
                if (target >= 0) scope.launch { repository.move(app.component, target) }
            },
            onUnpin = { app -> scope.launch { repository.unpin(app.component) } },
            onHide = { app -> scope.launch { repository.hide(app.component) } },
            installedCount = installed?.size,
            focusAllAppsOnEnter = focusAllAppsOnEnter,
        )
    }
}

/**
 * The all-apps drawer over [AppsRepository.apps] (every non-hidden app, A to Z), with [category] filtering in
 * place. Long-press OK on a tile offers Pin or Unpin and Hide. Put the chips row in [header].
 */
@Composable
fun AppDrawerScreen(
    repository: AppsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    category: AppCategory? = null,
    state: AppGridState = rememberAppGridState(),
    header: @Composable () -> Unit = {},
) {
    val apps by repository.apps.collectAsStateWithLifecycle<List<LauncherApp>?>(initialValue = null)
    val favorites by repository.favorites.collectAsStateWithLifecycle<List<LauncherApp>?>(initialValue = null)
    val scope = rememberCoroutineScope()

    val shown = remember(apps, category) { apps.orEmpty().filterBy(category) }
    val pinned = remember(favorites) { favorites.orEmpty().map { it.component }.toSet() }

    AppDrawer(
        apps = shown,
        onLaunch = { repository.launch(it) },
        onBack = onBack,
        modifier = modifier,
        pinned = pinned,
        onPin = { app -> scope.launch { repository.pin(app.component) } },
        onUnpin = { app -> scope.launch { repository.unpin(app.component) } },
        onHide = { app -> scope.launch { repository.hide(app.component) } },
        state = state,
        header = header,
    )
}

private fun List<LauncherApp>.filterBy(category: AppCategory?): List<LauncherApp> =
    if (category == null) this else filter { it.category == category }

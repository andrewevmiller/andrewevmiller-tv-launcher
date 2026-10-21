package com.andrewevmiller.tvlauncher.home.data

import android.content.ComponentName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * In-memory [AppsRepository] over [PreviewApps] for @Preview functions and UI tests.
 * Layout lives in the given [LayoutStore], so pin/hide/move behave like the real thing.
 */
class FakeAppsRepository(
    private val installed: List<LauncherApp> = PreviewApps,
    private val store: LayoutStore = InMemoryLayoutStore(
        FieldLayout(pinned = PreviewApps.take(6).map { it.component.flattenToString() }),
    ),
    /** Called by [launch]; previews can ignore it. */
    private val onLaunch: (LauncherApp) -> Unit = {},
) : AppsRepository {

    private val all = MutableStateFlow(installed)

    override val apps: Flow<List<LauncherApp>> =
        combine(all, store.layout) { apps, layout ->
            apps.filter { it.component.flattenToString() !in layout.hidden }.sortedBy { it.label.lowercase() }
        }

    override val favorites: Flow<List<LauncherApp>> =
        combine(all, store.layout) { apps, layout ->
            val byKey = apps.associateBy { it.component.flattenToString() }
            layout.pinned.mapNotNull { byKey[it] }.filter { it.component.flattenToString() !in layout.hidden }
        }

    override val hidden: Flow<List<LauncherApp>> =
        combine(all, store.layout) { apps, layout ->
            apps.filter { it.component.flattenToString() in layout.hidden }
        }

    override suspend fun pin(component: ComponentName) = store.update { layout ->
        val key = component.flattenToString()
        if (key in layout.pinned) layout else layout.copy(pinned = layout.pinned + key, hidden = layout.hidden - key)
    }

    override suspend fun unpin(component: ComponentName) = store.update { layout ->
        layout.copy(pinned = layout.pinned - component.flattenToString())
    }

    override suspend fun move(component: ComponentName, toIndex: Int) = store.update { layout ->
        val key = component.flattenToString()
        if (key !in layout.pinned) return@update layout
        val rest = layout.pinned - key
        layout.copy(pinned = rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), key) })
    }

    override suspend fun hide(component: ComponentName) = store.update { layout ->
        val key = component.flattenToString()
        layout.copy(pinned = layout.pinned - key, hidden = layout.hidden + key)
    }

    override suspend fun unhide(component: ComponentName) = store.update { layout ->
        layout.copy(hidden = layout.hidden - component.flattenToString())
    }

    override fun launch(app: LauncherApp) = onLaunch(app)
}

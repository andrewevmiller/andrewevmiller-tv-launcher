package com.andrewevmiller.tvlauncher.home.data

import android.content.ComponentName
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow

@Immutable
data class LauncherApp(
    val component: ComponentName,
    val label: String,
    val category: AppCategory,
    /** The app's 16:9 leanback banner if it has one, else its icon. Null while loading or if neither exists. */
    val artwork: ImageBitmap?,
    /** True when [artwork] is a real 16:9 banner (draw full-bleed); false for a square icon (draw centered). */
    val artworkIsBanner: Boolean,
)

/**
 * Source of installed apps plus the user's pinned/hidden layout.
 * Implementations persist layout state on the device.
 */
interface AppsRepository {
    /** Every launchable app that isn't hidden, sorted by label. */
    val apps: Flow<List<LauncherApp>>

    /** Pinned apps in the user's order. */
    val favorites: Flow<List<LauncherApp>>

    /** Hidden apps, so they can be un-hidden from settings. */
    val hidden: Flow<List<LauncherApp>>

    suspend fun pin(component: ComponentName)
    suspend fun unpin(component: ComponentName)
    /** Moves a pinned app to [toIndex] within favorites. */
    suspend fun move(component: ComponentName, toIndex: Int)
    suspend fun hide(component: ComponentName)
    suspend fun unhide(component: ComponentName)

    fun launch(app: LauncherApp)
}

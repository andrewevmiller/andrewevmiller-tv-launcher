package com.andrewevmiller.tvlauncher.home.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The user's home-screen layout, keyed by `ComponentName.flattenToString()`
 * (for example `com.plexapp.android/.activity.SplashActivity`).
 *
 * [pinned] is ordered. An app is in at most one of [pinned] and [hidden].
 */
data class FieldLayout(
    val pinned: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
)

/**
 * Persistence for [FieldLayout]. `AppsRepository` is built on top of this; the DataStore-backed
 * implementation lives next to it, and tests use [InMemoryLayoutStore].
 */
interface LayoutStore {
    val layout: Flow<FieldLayout>
    suspend fun update(transform: (FieldLayout) -> FieldLayout)
}

class InMemoryLayoutStore(initial: FieldLayout = FieldLayout()) : LayoutStore {
    private val state = MutableStateFlow(initial)
    override val layout: Flow<FieldLayout> = state
    override suspend fun update(transform: (FieldLayout) -> FieldLayout) = state.update(transform)
}

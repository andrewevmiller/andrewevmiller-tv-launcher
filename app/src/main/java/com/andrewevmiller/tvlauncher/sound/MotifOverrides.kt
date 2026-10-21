package com.andrewevmiller.tvlauncher.sound

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-app launch-motif overrides, keyed by package name. Implemented by the sound module
 * (persisted alongside its other data); consumed by Settings → Sound.
 *
 * A re-roll changes the motif seed to `fnv1a32(packageName + "#" + rerollCount)`.
 */
data class MotifOverride(
    val rerollCount: Int = 0,
    val muted: Boolean = false,
)

interface MotifOverrides {
    /** Only packages with a non-default override appear in the map. */
    val overrides: Flow<Map<String, MotifOverride>>

    /** Bumps the app's re-roll count so it gets a new motif. */
    suspend fun reroll(packageName: String)

    suspend fun setMuted(packageName: String, muted: Boolean)

    /** Back to the package-name-derived motif, unmuted. */
    suspend fun reset(packageName: String)
}

/** In-memory implementation for previews and tests. */
class FakeMotifOverrides(initial: Map<String, MotifOverride> = emptyMap()) : MotifOverrides {
    private val state = MutableStateFlow(initial)
    override val overrides: Flow<Map<String, MotifOverride>> = state

    override suspend fun reroll(packageName: String) = change(packageName) { it.copy(rerollCount = it.rerollCount + 1) }
    override suspend fun setMuted(packageName: String, muted: Boolean) = change(packageName) { it.copy(muted = muted) }
    override suspend fun reset(packageName: String) = state.update { it - packageName }

    private fun change(packageName: String, f: (MotifOverride) -> MotifOverride) = state.update { map ->
        val next = f(map[packageName] ?: MotifOverride())
        if (next == MotifOverride()) map - packageName else map + (packageName to next)
    }
}

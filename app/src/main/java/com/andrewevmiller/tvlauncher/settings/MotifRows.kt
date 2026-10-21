package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.sound.MotifOverride
import com.andrewevmiller.tvlauncher.sound.MotifOverrides
import kotlinx.coroutines.delay

/*
 * Row model for Settings -> Sound -> per-app launch motifs. Pure Kotlin: the screen maps each
 * LauncherApp to a [MotifApp] and renders whatever [buildMotifRows] returns.
 */

/** The two facts about an app that the motif list needs. */
data class MotifApp(val label: String, val packageName: String)

/** One line of the per-app motif list. */
data class MotifRow(
    val label: String,
    val packageName: String,
    val override: MotifOverride = MotifOverride(),
) {
    /** True when the app has a re-rolled or muted motif, so Reset has something to undo. */
    val isOverridden: Boolean get() = override != MotifOverride()

    /** Mono status text: `rerolled 3×`, `muted`, `rerolled 3× · muted`, or null for an untouched app. */
    val statusLabel: String?
        get() {
            val parts = ArrayList<String>(2)
            if (override.rerollCount > 0) parts += "rerolled ${override.rerollCount}×"
            if (override.muted) parts += "muted"
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

    /** LazyColumn key: one row per package, whatever else changes. */
    val key: String get() = "motif:$packageName"
}

/**
 * Builds the list shown under the Sound section.
 *
 * - One row per package: an app with several launcher activities appears once (its first label in sort order).
 * - Sorted by label, case-insensitively, then by package name, so the order is deterministic and stable when an
 *   override changes (a re-roll never moves its own row, which would drop D-pad focus).
 * - Overrides for packages that are not installed (or are hidden) are ignored.
 */
fun buildMotifRows(apps: List<MotifApp>, overrides: Map<String, MotifOverride>): List<MotifRow> =
    apps
        .sortedWith(compareBy<MotifApp>({ it.label.lowercase() }, { it.label }, { it.packageName }))
        .distinctBy { it.packageName }
        .map { MotifRow(it.label, it.packageName, overrides[it.packageName] ?: MotifOverride()) }

/**
 * Re-rolls an app's motif, waits [settle] so the sound engine can re-render it, then calls [play]
 * so the owner hears the new motif.
 */
suspend fun rerollMotif(
    overrides: MotifOverrides,
    packageName: String,
    settle: suspend () -> Unit = { delay(SoundFeedback.SETTLE_MS) },
    play: () -> Unit,
) {
    overrides.reroll(packageName)
    settle()
    play()
}

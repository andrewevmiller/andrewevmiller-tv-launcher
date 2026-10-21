package com.andrewevmiller.tvlauncher.home.data

import android.content.ComponentName

/** Artwork-less sample data for @Preview functions and placeholder rows. */
val PreviewApps: List<LauncherApp> = listOf(
    "YouTube" to AppCategory.VIDEO,
    "Netflix" to AppCategory.VIDEO,
    "Plex" to AppCategory.VIDEO,
    "Spotify" to AppCategory.MUSIC,
    "Stadia Notes" to AppCategory.GAMES,
    "Files" to AppCategory.TOOLS,
).map { (label, category) ->
    LauncherApp(
        component = ComponentName("preview.${label.lowercase().replace(' ', '_')}", "Main"),
        label = label,
        category = category,
        artwork = null,
        artworkIsBanner = false,
    )
}

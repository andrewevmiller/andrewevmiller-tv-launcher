package com.andrewevmiller.tvlauncher.home.data

// Pure Kotlin on purpose (no android.* imports): the values below mirror ApplicationInfo.CATEGORY_*
// so this file can be unit-tested on the plain JVM.
private const val ANDROID_CATEGORY_GAME = 0
private const val ANDROID_CATEGORY_AUDIO = 1
private const val ANDROID_CATEGORY_VIDEO = 2
private const val ANDROID_CATEGORY_PRODUCTIVITY = 7

// Package hints. A hint matches when any dot- or underscore-separated segment of the (lower-cased)
// package name starts with it, so "files" matches com.google.android.apps.nbu.files but not
// com.example.profiles, and "hbo" does not match com.neighborhood.app.
private val MUSIC_HINTS = listOf("spotify", "tidal", "deezer", "pandora", "soundcloud", "music", "tvmusic")
private val VIDEO_HINTS = listOf(
    "youtube", "netflix", "plex", "disney", "hulu", "primevideo", "amazonvideo", "hbo", "tubi",
    "twitch", "crunchyroll", "paramount", "peacock", "kodi", "jellyfin", "emby", "vlc",
)
private val GAMES_HINTS = listOf("limelight", "retroarch", "steam", "moonlight", "game")
private val TOOLS_HINTS = listOf(
    "filemanager", "files", "fileexplorer", "filebrowser", "analyzer", "settings", "terminal", "termux", "tool",
)

private fun hasHint(segments: List<String>, hints: List<String>): Boolean =
    segments.any { segment -> hints.any { hint -> segment.startsWith(hint) } }

/**
 * Maps an installed app to the coarse [AppCategory] used by the chip filters and by the launch motif's timbre.
 *
 * [androidCategory] is `ApplicationInfo.category` as an Int (CATEGORY_UNDEFINED = -1).
 *
 * Known package hints win, then the Android category, else [AppCategory.OTHER]. Where hints from two
 * categories could match the same package, the order is music, video, games, tools: that keeps
 * `com.google.android.youtube.tvmusic` (YouTube Music) out of VIDEO.
 */
fun categorize(packageName: String, androidCategory: Int): AppCategory {
    val segments = packageName.lowercase().split('.', '_')
    return when {
        hasHint(segments, MUSIC_HINTS) -> AppCategory.MUSIC
        hasHint(segments, VIDEO_HINTS) -> AppCategory.VIDEO
        hasHint(segments, GAMES_HINTS) -> AppCategory.GAMES
        hasHint(segments, TOOLS_HINTS) -> AppCategory.TOOLS
        androidCategory == ANDROID_CATEGORY_GAME -> AppCategory.GAMES
        androidCategory == ANDROID_CATEGORY_AUDIO -> AppCategory.MUSIC
        androidCategory == ANDROID_CATEGORY_VIDEO -> AppCategory.VIDEO
        androidCategory == ANDROID_CATEGORY_PRODUCTIVITY -> AppCategory.TOOLS
        else -> AppCategory.OTHER
    }
}

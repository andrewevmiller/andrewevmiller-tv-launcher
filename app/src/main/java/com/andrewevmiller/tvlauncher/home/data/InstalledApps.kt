package com.andrewevmiller.tvlauncher.home.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * Shared helpers for turning an installed app into a tile (artwork, category) and for launching it.
 * Listing and caching the apps is [AppLoader]'s job.
 */
object InstalledApps {
    /** Rasterizes [d] at the size its tile needs: 16:9 for a banner, square for an icon. */
    internal fun toBitmap(d: Drawable, banner: Boolean): Bitmap {
        val w = if (banner) 384 else 192
        val h = if (banner) 216 else 192
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(it))
        }
    }

    private val videoHints = listOf("video", "netflix", "youtube", "plex", "hulu", "disney", "crunchyroll", "twitch", "tubi", "pluto", "abc", "cbs", "nbc", "hbo", "peacock", "paramount", "iptv", "vlc", "kodi")
    private val musicHints = listOf("music", "spotify", "tidal", "pandora", "audio", "podcast", "radio", "deezer")
    private val gameHints = listOf("game", "steam", "moonlight", "retroarch", "emulator", "stadia", "xbox")
    private val toolHints = listOf("settings", "file", "manager", "download", "browser", "vpn", "assistant", "cleaner", "analyzer", "tools")

    /** The manifest category when the app set one, else a guess from the package name. */
    @Suppress("DEPRECATION")
    internal fun categoryOf(ai: ActivityInfo): AppCategory {
        when (ai.applicationInfo.category) {
            ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
            ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.MUSIC
            ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAMES
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.TOOLS
        }
        val pkg = ai.packageName.lowercase()
        return when {
            gameHints.any { it in pkg } -> AppCategory.GAMES
            musicHints.any { it in pkg } -> AppCategory.MUSIC
            videoHints.any { it in pkg } -> AppCategory.VIDEO
            toolHints.any { it in pkg } -> AppCategory.TOOLS
            else -> AppCategory.OTHER
        }
    }

    fun launch(context: Context, app: LauncherApp) {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
    }
}

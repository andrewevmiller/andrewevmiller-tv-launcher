package com.andrewevmiller.tvlauncher.home.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Reads every launchable app on the device (leanback first, then regular launcher activities). */
object InstalledApps {
    fun load(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        val self = context.packageName
        val seen = HashSet<String>()
        val result = ArrayList<LauncherApp>()
        for ((pass, category) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).withIndex()) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val ai = info.activityInfo
                if (ai.packageName == self || !seen.add(ai.packageName)) continue
                val banner = runCatching { ai.loadBanner(pm) }.getOrNull()
                val drawable = banner ?: runCatching { info.loadIcon(pm) }.getOrNull()
                result += LauncherApp(
                    component = ComponentName(ai.packageName, ai.name),
                    label = info.loadLabel(pm).toString(),
                    category = categoryOf(ai),
                    pinned = pass == 0,
                    artwork = drawable?.let { toBitmap(it, banner != null) }?.asImageBitmap(),
                    artworkIsBanner = banner != null,
                )
            }
        }
        return result.sortedBy { it.label.lowercase() }
    }

    private fun toBitmap(d: Drawable, banner: Boolean): Bitmap {
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
    private fun categoryOf(ai: ActivityInfo): AppCategory {
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

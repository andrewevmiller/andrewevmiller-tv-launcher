package com.andrewevmiller.tvlauncher.home.data

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ArtworkLoader"

/** Pixel size banners are rendered at (16:9). */
internal const val BANNER_WIDTH_PX = 320
internal const val BANNER_HEIGHT_PX = 180

/** Pixel size (square) icons are rendered at. */
internal const val ICON_SIZE_PX = 192

/** Upper bound for the in-memory artwork cache. About 100 banners fit. */
private const val CACHE_BYTES = 32 * 1024 * 1024

/** Rendered artwork for one app. */
class Artwork(val image: ImageBitmap, val isBanner: Boolean, internal val byteCount: Int)

/**
 * Loads an app's tile artwork: its 16:9 leanback banner (activity banner, then application banner) or, failing
 * that, its icon. Drawables are rendered to bitmaps on [Dispatchers.Default], banners at
 * [BANNER_WIDTH_PX] x [BANNER_HEIGHT_PX] and icons at [ICON_SIZE_PX] square, and cached by component plus
 * the package's last update time, so an updated app reloads its art.
 */
class ArtworkLoader(private val pm: PackageManager) {
    private val cache = object : LruCache<String, Artwork>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Artwork): Int = value.byteCount
    }

    private fun cacheKey(app: RawApp): String = "${app.key}@${app.lastUpdateTime}"

    /** The cached artwork for [app], or null if it has not been loaded yet. Never blocks. */
    fun peek(app: RawApp): Artwork? = cache.get(cacheKey(app))

    /** Returns the cached artwork or loads it off the main thread. Null if the app has neither banner nor icon. */
    suspend fun load(app: RawApp): Artwork? = withContext(Dispatchers.Default) {
        val key = cacheKey(app)
        val cached = cache.get(key)
        if (cached != null) {
            cached
        } else {
            val rendered = render(app)
            if (rendered != null) cache.put(key, rendered)
            rendered
        }
    }

    private fun render(app: RawApp): Artwork? {
        val info = app.activityInfo
        val banner = try {
            info.loadBanner(pm) ?: info.applicationInfo?.loadBanner(pm)
        } catch (e: Exception) {
            Log.w(TAG, "Banner failed for ${app.key}", e)
            null
        }
        if (banner != null) {
            val bitmap = draw(banner, BANNER_WIDTH_PX, BANNER_HEIGHT_PX, cover = true)
            if (bitmap != null) return Artwork(bitmap.asImageBitmap(), isBanner = true, byteCount = bitmap.byteCount)
        }
        val icon = try {
            info.loadIcon(pm)
        } catch (e: Exception) {
            Log.w(TAG, "Icon failed for ${app.key}", e)
            null
        }
        if (icon != null) {
            val bitmap = draw(icon, ICON_SIZE_PX, ICON_SIZE_PX, cover = false)
            if (bitmap != null) return Artwork(bitmap.asImageBitmap(), isBanner = false, byteCount = bitmap.byteCount)
        }
        return null
    }

    /** Draws [drawable] centered into a new ARGB_8888 bitmap, or returns null if drawing fails. */
    private fun draw(drawable: Drawable, width: Int, height: Int, cover: Boolean): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rect = fitRect(drawable.intrinsicWidth, drawable.intrinsicHeight, width, height, cover)
            drawable.setBounds(rect.left, rect.top, rect.right, rect.bottom)
            drawable.draw(Canvas(bitmap))
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Could not render drawable", e)
            null
        }
    }
}

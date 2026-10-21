package com.andrewevmiller.tvlauncher.ambient

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.andrewevmiller.tvlauncher.theme.DarkFieldColors
import com.andrewevmiller.tvlauncher.ui.ScreenshotSupport
import java.io.File
import kotlin.random.Random
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/**
 * Hosts an [OxyfieldView] in a Robolectric activity at the TV's 1920x1080 / density 320 and drives its frames by
 * hand, so tests need no Choreographer. Tests using it need `@GraphicsMode(NATIVE)` and the
 * `w960dp-h540dp-xhdpi` qualifier, like the other screenshot tests (see [ScreenshotSupport]).
 *
 * Time runs on the view's own millisecond clock: [advanceTo] moves it to `start + millis`, in steps of
 * [stepMillis] like the real loop would, so ripples spawn and expire as they would on the TV.
 */
internal class SleepDimRig(reducedMotion: Boolean = false, seed: Int = 7) {
    val palette = OxyfieldPalette(
        bg = DarkFieldColors.bg.toArgb(),
        dot = DarkFieldColors.line.toArgb(),
        muted = DarkFieldColors.muted.toArgb(),
        accent = DarkFieldColors.accent.toArgb(),
        ink = DarkFieldColors.ink.toArgb(),
    )

    val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = OxyfieldView(activity, palette, reducedMotion, Typeface.MONOSPACE, Random(seed))
    val width: Int get() = view.width
    val height: Int get() = view.height

    /** The view's clock at "0 s". */
    val startMillis: Long

    private var cursor = 0L

    init {
        activity.setContentView(view)
        view.stop() // we step frames ourselves; no Choreographer callbacks
        // Lay the decor out once so the view has its size (and its ripple field) before the clock starts.
        ScreenshotSupport.capture(activity, "sleep-dim-layout").delete()
        startMillis = System.nanoTime() / 1_000_000L
        view.advanceTo(startMillis)
    }

    /** Runs frames from the last position up to [millis] after the start, [stepMillis] apart. */
    fun advanceTo(millis: Long, stepMillis: Long = 100L) {
        while (cursor < millis) {
            cursor = minOf(cursor + stepMillis, millis)
            view.advanceTo(startMillis + cursor)
        }
    }

    /** Draws the decor view into a bitmap, the way [ScreenshotSupport.capture] does. */
    fun render(): Bitmap {
        val decor = activity.window.decorView
        val w = activity.resources.displayMetrics.widthPixels
        val h = activity.resources.displayMetrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap
    }

    /** Writes the current frame to app/build/screenshots/[name].png. */
    fun screenshot(name: String): File = ScreenshotSupport.capture(activity, name)

    companion object {
        /** The brightest channel of any pixel in the rectangle. */
        fun maxChannel(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
            var best = 0
            for (y in top until bottom) {
                for (x in left until right) {
                    val p = bitmap.getPixel(x, y)
                    val m = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                    if (m > best) best = m
                }
            }
            return best
        }

        /** Number of pixels whose brightest channel is above [threshold]. */
        fun countAbove(bitmap: Bitmap, threshold: Int): Int {
            var n = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val p = bitmap.getPixel(x, y)
                    val m = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                    if (m > threshold) n++
                }
            }
            return n
        }

        /** Mean of the brightest channel over all pixels. */
        fun meanLevel(bitmap: Bitmap): Double {
            var sum = 0L
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val p = bitmap.getPixel(x, y)
                    sum += maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                }
            }
            return sum.toDouble() / (bitmap.width.toLong() * bitmap.height)
        }
    }
}

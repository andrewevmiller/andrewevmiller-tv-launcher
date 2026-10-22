package com.andrewevmiller.tvlauncher.ambient

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.andrewevmiller.tvlauncher.theme.DarkFieldColors
import com.andrewevmiller.tvlauncher.ui.ScreenshotSupport
import java.io.File
import java.util.Calendar
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
 *
 * The wall clock the screensaver clock shows is a separate value ([wallMillis], default 2026-10-21 19:42:07.250 local
 * time) and the 12/24 hour choice is [is24Hour], so frames are deterministic. The wall clock follows the main
 * looper's uptime, like the real one: it is the value last assigned plus the time the test has advanced the looper
 * since (`idleFor`), so a timer aimed at the next whole second lands where it would on the TV. Assigning a value
 * re-anchors it, which is how a test jumps the clock by a minute or a time zone's worth. Neither moving it nor
 * reading it draws or notifies anything: it is what the real clock does between two frames.
 */
internal class SleepDimRig(
    reducedMotion: Boolean = false,
    seed: Int = 7,
    is24Hour: Boolean = true,
    wallMillis: Long = DEFAULT_WALL_MILLIS,
) {
    val palette = OxyfieldPalette(
        bg = DarkFieldColors.bg.toArgb(),
        dot = DarkFieldColors.line.toArgb(),
        muted = DarkFieldColors.muted.toArgb(),
        accent = DarkFieldColors.accent.toArgb(),
        ink = DarkFieldColors.ink.toArgb(),
    )

    val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = OxyfieldView(activity, palette, reducedMotion, Typeface.MONOSPACE, Random(seed))

    private var anchorWall: Long = wallMillis
    private var anchorUptime: Long = SystemClock.uptimeMillis()

    /**
     * What the screensaver clock reads as "now": the last value assigned plus the looper time that has passed since.
     * Assign to jump it (`wallMillis += 60_000L` is one minute later than now).
     */
    var wallMillis: Long
        get() = anchorWall + (SystemClock.uptimeMillis() - anchorUptime)
        set(value) {
            anchorWall = value
            anchorUptime = SystemClock.uptimeMillis()
        }

    /** The TV's 24-hour setting, as the view sees it. */
    var is24Hour: Boolean = is24Hour
    val width: Int get() = view.width
    val height: Int get() = view.height

    /** The view's clock at "0 s". */
    val startMillis: Long

    private var cursor = 0L

    init {
        view.wallClock = { this.wallMillis }
        view.is24Hour = { this.is24Hour }
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
        /** 2026-10-21 19:42:07.250 in the JVM's default time zone, which is also the view's. */
        val DEFAULT_WALL_MILLIS: Long = Calendar.getInstance().run {
            clear()
            set(2026, Calendar.OCTOBER, 21, 19, 42, 7)
            set(Calendar.MILLISECOND, 250)
            timeInMillis
        }

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

        /**
         * Number of pixels whose brightest channel is above [threshold], not counting any inside one of the
         * [exclude] rectangles (left, top, right, bottom), e.g. the clock lines when only the dots are of interest.
         */
        fun countAbove(bitmap: Bitmap, threshold: Int, exclude: List<IntArray> = emptyList()): Int {
            var n = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (exclude.any { x >= it[0] && x < it[2] && y >= it[1] && y < it[3] }) continue
                    val p = bitmap.getPixel(x, y)
                    val m = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                    if (m > threshold) n++
                }
            }
            return n
        }

        /** Number of pixels in the rectangle that are exactly [argb] (opaque), i.e. fully covered glyph pixels. */
        fun countExact(bitmap: Bitmap, argb: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
            var n = 0
            for (y in top until bottom) {
                for (x in left until right) {
                    if (bitmap.getPixel(x, y) == argb) n++
                }
            }
            return n
        }

        /**
         * The rectangle (left, top, right, bottom; right and bottom exclusive) that holds every pixel that differs
         * between [a] and [b], or null if they are identical.
         */
        fun diffBounds(a: Bitmap, b: Bitmap): IntArray? {
            var l = Int.MAX_VALUE
            var t = Int.MAX_VALUE
            var r = -1
            var bt = -1
            for (y in 0 until a.height) {
                for (x in 0 until a.width) {
                    if (a.getPixel(x, y) != b.getPixel(x, y)) {
                        if (x < l) l = x
                        if (x > r) r = x
                        if (y < t) t = y
                        if (y > bt) bt = y
                    }
                }
            }
            return if (r < 0) null else intArrayOf(l, t, r + 1, bt + 1)
        }

        /**
         * The bounding box (left, top, right, bottom; right and bottom exclusive) of the pixels all three of whose
         * channels are at least [minChannel], or null if there are none. With a high threshold that is the clock's
         * time text alone: ink is near white, and no dot colour (muted, accent, line) reaches it. With [within]
         * (left, top, right, bottom) only that part of the bitmap is searched.
         */
        fun brightBounds(bitmap: Bitmap, minChannel: Int, within: IntArray? = null): IntArray? {
            var l = Int.MAX_VALUE
            var t = Int.MAX_VALUE
            var r = -1
            var bt = -1
            val x0 = within?.get(0) ?: 0
            val y0 = within?.get(1) ?: 0
            val x1 = within?.get(2) ?: bitmap.width
            val y1 = within?.get(3) ?: bitmap.height
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val p = bitmap.getPixel(x, y)
                    if (((p shr 16) and 0xFF) >= minChannel && ((p shr 8) and 0xFF) >= minChannel && (p and 0xFF) >= minChannel) {
                        if (x < l) l = x
                        if (x > r) r = x
                        if (y < t) t = y
                        if (y > bt) bt = y
                    }
                }
            }
            return if (r < 0) null else intArrayOf(l, t, r + 1, bt + 1)
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

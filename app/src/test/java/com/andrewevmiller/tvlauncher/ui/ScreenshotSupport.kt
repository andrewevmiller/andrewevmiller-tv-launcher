package com.andrewevmiller.tvlauncher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import org.junit.Assume.assumeTrue
import org.robolectric.shadows.ShadowLooper

/**
 * Screenshots of the real UI, rendered on the JVM at the Hisense's geometry (1920x1080 px, density 320).
 *
 * Off by default so the normal test run stays fast: set FIELD_SCREENSHOTS=1 (or -Dfield.screenshots=1) to
 * produce PNGs in app/build/screenshots/. They are a visual aid, not assertions.
 *
 * Compose's own captureToImage() waits for a frame that Robolectric's paused looper never delivers, so the
 * decor view is laid out and drawn into a bitmap by hand. Needs `@GraphicsMode(NATIVE)` and a
 * `w960dp-h540dp-xhdpi` qualifier on the test class.
 */
object ScreenshotSupport {
    fun enabled(): Boolean =
        System.getenv("FIELD_SCREENSHOTS") != null || System.getProperty("field.screenshots") != null

    fun assumeEnabled() = assumeTrue("set FIELD_SCREENSHOTS=1 to render screenshots", enabled())

    fun capture(activity: Activity, name: String): File {
        val view = activity.window.decorView
        val w = activity.resources.displayMetrics.widthPixels
        val h = activity.resources.displayMetrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").also { it.mkdirs() }
        return File(dir, "$name.png").also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

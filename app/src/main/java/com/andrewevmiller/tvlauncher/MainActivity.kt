package com.andrewevmiller.tvlauncher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.lifecycle.lifecycleScope
import com.andrewevmiller.tvlauncher.safety.CrashGuard
import com.andrewevmiller.tvlauncher.theme.Geist
import com.andrewevmiller.tvlauncher.theme.PlexMono
import com.andrewevmiller.tvlauncher.theme.PlexSerif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Bumped when Home is pressed while we are already running; FieldRoot resets Home on each change. */
    private var homeResets by mutableIntStateOf(0)

    /** Bumped on the remote's Menu/Settings key; FieldRoot opens Settings. */
    private var settingsRequests by mutableIntStateOf(0)

    /** Set by FieldRoot once it has composed real content (it draws nothing until the stored theme has loaded). */
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableStrictModeInDebug()
        preloadFonts()
        dropWindowBackgroundOnceContentIsDrawn()
        // Debug-only hook for testing the crash-loop fallback: adb ... --ez field.forceCrash true
        CrashGuard.maybeForceCrash(this, intent)
        // A launcher is the bottom of the stack; Back has nowhere to go. Drawer and Settings add their own
        // BackHandlers on top of this one.
        onBackPressedDispatcher.addCallback(this) { }
        val container = (application as FieldApplication).container
        setContent {
            FieldRoot(
                container = container,
                homeResets = homeResets,
                settingsRequests = settingsRequests,
                onContentReady = { contentReady = true },
            )
        }
    }

    /**
     * In a debuggable build, log (never crash on) disk and network work on the main thread and leaked
     * closeables; look for the "StrictMode" tag in logcat. Release builds are untouched. AGP 8 generates
     * no BuildConfig by default, so debuggability comes from the app's flags.
     */
    private fun enableStrictModeInDebug() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
        )
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build(),
        )
    }

    /**
     * The theme's window background stops a flash before the first frame. Once Compose has drawn real content
     * its own root paints the same colour over the whole screen, so the window's copy is a second full-screen
     * fill on every frame for nothing: drop it. FieldRoot draws nothing until the stored theme has loaded (so a
     * light-mode user never sees a dark flash), so wait for [contentReady] and not just the first draw.
     */
    private fun dropWindowBackgroundOnceContentIsDrawn() {
        val view = window.decorView
        view.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            private var done = false
            override fun onDraw() {
                if (done || !contentReady) return
                done = true
                view.post {
                    window.setBackgroundDrawable(null)
                    view.viewTreeObserver.removeOnDrawListener(this)
                }
            }
        })
    }

    /** Reads the font files on a background thread now, so the first frame doesn't wait for them on the main thread. */
    private fun preloadFonts() {
        val resolver = createFontFamilyResolver(this)
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                resolver.preload(PlexSerif)
                resolver.preload(Geist)
                resolver.preload(PlexMono)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeResets++
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            settingsRequests++
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

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
import com.andrewevmiller.tvlauncher.safety.CrashGuard

class MainActivity : ComponentActivity() {
    /** Bumped when Home is pressed while we are already running; FieldRoot resets Home on each change. */
    private var homeResets by mutableIntStateOf(0)

    /** Bumped on the remote's Menu/Settings key; FieldRoot opens Settings. */
    private var settingsRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug-only hook for testing the crash-loop fallback: adb ... --ez field.forceCrash true
        CrashGuard.maybeForceCrash(this, intent)
        // A launcher is the bottom of the stack; Back has nowhere to go. Drawer and Settings add their own
        // BackHandlers on top of this one.
        onBackPressedDispatcher.addCallback(this) { }
        val container = (application as FieldApplication).container
        setContent {
            FieldRoot(container = container, homeResets = homeResets, settingsRequests = settingsRequests)
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

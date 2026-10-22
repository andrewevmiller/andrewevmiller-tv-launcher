package com.andrewevmiller.tvlauncher.remote

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andrewevmiller.tvlauncher.panel.NotificationPanelActivity

/** What a remapped button does. */
sealed interface KeyAction {
    /** The notifications panel (with its Open Settings shortcut). */
    data object Panel : KeyAction

    /** Bring an app to the front. */
    data class OpenApp(val component: ComponentName) : KeyAction
}

/**
 * Which remote buttons the launcher takes over, and what they do. Only buttons listed here are
 * touched; everything else reaches the system exactly as before.
 *
 * Codes are the Android key codes read from this TV's remote: its Settings button sends
 * NOTIFICATION, its Tuner button sends GUIDE, its User button sends PROFILE_SWITCH. The system hands
 * all three to the Google TV launcher, which is disabled here, so by default they do nothing.
 */
object RemoteKeys {
    val STREMIO = ComponentName("com.stremio.one", "com.stremio.tv.MainActivity")

    val mapping: Map<Int, KeyAction> = mapOf(
        KeyEvent.KEYCODE_NOTIFICATION to KeyAction.Panel,
        KeyEvent.KEYCODE_GUIDE to KeyAction.OpenApp(STREMIO),
    )

    /** Never remappable, so a bad mapping can not leave the TV without navigation or Home. */
    private val protectedKeys = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
    )

    fun actionFor(keyCode: Int): KeyAction? = if (keyCode in protectedKeys) null else mapping[keyCode]
}

/**
 * Sees every remote key press before the system's own key handling, and takes over the ones in
 * [RemoteKeys]. It reads nothing from the screen and does nothing with other keys. It needs
 * switching on once with `tools/remote.ps1 enable`; switching it off restores every button.
 */
class RemoteKeyService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action = RemoteKeys.actionFor(event.keyCode) ?: return false
        // Act on the press itself; swallow its repeats and its release too so the system never
        // sees half a key.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) run(action)
        return true
    }

    private fun run(action: KeyAction) {
        val intent = when (action) {
            KeyAction.Panel -> Intent(this, NotificationPanelActivity::class.java)
                .setAction("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL")
            is KeyAction.OpenApp -> Intent(Intent.ACTION_MAIN).setComponent(action.component)
        }
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }
}

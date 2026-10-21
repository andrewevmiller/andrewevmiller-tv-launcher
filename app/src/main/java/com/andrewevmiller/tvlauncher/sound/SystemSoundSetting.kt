package com.andrewevmiller.tvlauncher.sound

import android.content.Context
import android.provider.Settings

/**
 * Whether the system-wide "Sound effects" switch is on. A missing key counts as on, matching Android's default.
 * Android glue, so purecheck skips this file; the policy itself is [soundsAllowed].
 */
fun systemSoundEffectsEnabled(context: Context): Boolean =
    Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0

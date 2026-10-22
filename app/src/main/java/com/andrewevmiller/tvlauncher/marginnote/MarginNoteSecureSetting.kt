package com.andrewevmiller.tvlauncher.marginnote

import android.content.Context
import android.provider.Settings

/**
 * Reads the laptop fallback for the margin note: `adb shell settings put secure field_launcher_note "text"`
 * (wrapped by scripts/note.sh). Reading `Settings.Secure` needs no permission, the same as the recorded
 * previous home. Any failure reads as "no setting".
 */
object MarginNoteSecureSetting {
    /** The secure-settings key. */
    const val SETTING = "field_launcher_note"

    /** The raw value, or null when unset or unreadable. */
    fun read(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, SETTING)
    } catch (t: Throwable) {
        null
    }

    /** The note Home shows: the stored note if it has text, otherwise the secure-setting note. */
    fun mergedNote(context: Context, stored: String): String = MarginNoteRules.merge(stored, read(context))
}

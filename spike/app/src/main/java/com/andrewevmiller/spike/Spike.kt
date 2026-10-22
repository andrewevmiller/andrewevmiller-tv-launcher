package com.andrewevmiller.spike

/** Constants shared by the two probes. */
object Spike {
    /** Every result line is also written to logcat under this tag: `adb logcat -s FieldSpike`. */
    const val TAG = "FieldSpike"

    const val PERM_READ_EPG = "com.android.providers.tv.permission.READ_EPG_DATA"
    const val PERM_ACCESS_ALL_EPG = "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA"
    const val PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    /** Google TV ambient mode (the Google Photos slideshow). */
    const val DREAM_PACKAGE = "com.google.android.apps.tv.dreamx"
    const val BACKDROP = "com.google.android.apps.tv.dreamx/.service.Backdrop"

    /** AOSP SystemUI activity that calls Sandman.startDreamByUserRequest(). */
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val SOMNAMBULATOR_CLASS = "com.android.systemui.Somnambulator"

    /** Settings.Secure key for the active screensaver (SCREENSAVER_COMPONENTS is @hide, so use the name). */
    const val SETTING_SCREENSAVER = "screensaver_components"

    const val PREFS = "spike"
    const val PREF_PREV_VALUE = "prev_screensaver_components"
    const val PREF_PREV_SAVED = "prev_screensaver_saved"

    /** Stored in prefs in place of a null Settings value, so "was null" survives a restart. */
    const val NULL_SENTINEL = "<null>"
}

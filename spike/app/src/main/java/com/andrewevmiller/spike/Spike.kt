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

    // ---- E3 (sleep timer) ----------------------------------------------------------------------

    /** Intent extras on the alarm broadcast and on SleepDimActivity. */
    const val EXTRA_METHOD = "e3_method"
    const val EXTRA_RUN = "e3_run"

    /** The alarm fires this long after the button press: time to start a video in another app. */
    const val E3_DELAY_MS = 20_000L

    /** How long after startActivity() the receiver waits for SleepDimActivity.onCreate (or the dream signal). */
    const val E3_DIM_WINDOW_MS = 3_000L

    /** Pause between the rungs of the pause ladder. */
    const val E3_RUNG_WAIT_MS = 1_500L

    /** How long P3 holds audio focus before it checks and lets go. */
    const val E3_FOCUS_HOLD_MS = 3_000L

    /** Counter for run ids, kept in the shared prefs file. */
    const val PREF_E3_LAST_ID = "e3_last_id"

    const val REQ_E3_ALARM = 31
    const val REQ_E3_TEST = 32
    const val REQ_E3_SHOW = 33

    /** SleepDimActivity background and text, near-black like the real dim screen. */
    const val DIM_BG = 0xFF0A0A09.toInt()
    const val DIM_TEXT = 0xFF8A8982.toInt()
}

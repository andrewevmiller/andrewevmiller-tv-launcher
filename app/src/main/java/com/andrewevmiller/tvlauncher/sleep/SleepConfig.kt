package com.andrewevmiller.tvlauncher.sleep

/*
 * The two numbers the owner flips after the TV spike (spike/README.md section 6, E3), and the timings the
 * Android side shares. Pure, so the tests read the same constants the app uses.
 */
object SleepConfig {
    /**
     * The pause rung the spike proved on the TV: one of [PauseRungs.MEDIA_KEY], [PauseRungs.MEDIA_KEY_RETRY] or
     * [PauseRungs.AUDIO_FOCUS]. Null keeps [PauseRungs.DEFAULT_ORDER]. The proven rung is tried first; the rest stay
     * as fallbacks, so a wrong value costs a second or two, never the pause.
     */
    val PROVEN_PAUSE_RUNG: String? = null

    /**
     * The dim-screen strategy the spike proved: [DimStrategies.OWN_ACTIVITY] or [DimStrategies.SCREENSAVER]. Null
     * keeps [DimStrategies.DEFAULT_ORDER]. Same rule as above: moved to the front, the others stay as fallbacks.
     */
    val PROVEN_DIM_STRATEGY: String? = null

    /** Logcat tag for everything the end sequence does: `adb logcat -s FieldSleep`. */
    const val TAG = "FieldSleep"

    /** SharedPreferences file that keeps the running timer across a process restart. */
    const val PREFS_NAME = "field_sleep"

    /**
     * A stored deadline up to this far in the PAST is not stale, it is the alarm that is firing right now into a
     * process that had to be started for it (cold start plus scheduling delay). Older than this the TV was off or the
     * launcher was away at the deadline, and the timer is dropped, never fired late.
     */
    const val DUE_GRACE_MILLIS = 120_000L

    /** An alarm may arrive a little before the stored deadline (wall clock stepped by a time sync). Still due. */
    const val EARLY_TOLERANCE_MILLIS = 5_000L

    /** A just-due timer found on startup also gets a back-up alarm this far ahead, in case the original was lost. */
    const val FALLBACK_DELAY_MILLIS = 2_000L

    /** The receiver finishes its PendingResult by this time whatever the sequence is doing (the system allows ~10 s). */
    const val RELEASE_WATCHDOG_MILLIS = 8_500L

    /** After asking for a dim screen, how long to wait for proof that it came up, and how often to look. */
    const val DIM_WAIT_MILLIS = 3_000L
    const val DIM_POLL_MILLIS = 100L

    /**
     * Audio focus is held this long on top of the ladder's own settle time before the check, so the total hold is the
     * spike's 3 s (1.5 s here + 1.5 s settle).
     */
    const val FOCUS_EXTRA_HOLD_MILLIS = 1_500L

    /** The dim screen ignores keys this long after it appears (a key still being released must not wake it). */
    const val DIM_KEY_GRACE_MILLIS = 700L

    /** The dim screen closes itself after this long, so it can never strand the TV on a dark screen. */
    const val DIM_AUTO_FINISH_MILLIS = 30L * 60_000L
}
